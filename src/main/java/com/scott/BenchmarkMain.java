package com.scott;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.scott.profiling.AsyncProfilerProfiler;
import com.scott.profiling.CompositeProfiler;
import com.scott.profiling.JfrProfiler;
import com.scott.profiling.MeasurementContext;
import com.scott.profiling.PerfProfiler;
import com.scott.profiling.Profiler;
import com.scott.profiling.ProfilingSession;
import com.scott.profiling.RunMetadataWriter;

/**
 * YAML-driven benchmark runner. Profiling is driven by
 * {@link ProfilingSession} — no profiler-specific code remains in this
 * class apart from session construction.
 */
public class BenchmarkMain {

    private static final int MIN_USEFUL_SAMPLES = 100;

    /**
     * Tiny sink for the optional {@code task_execution_times.csv}
     * streaming export. Holds the writer plus enough context to compute
     * {@code shardId} for each completed task. Synchronisation is on
     * the writer itself; the JDK's {@link BufferedWriter} is not
     * thread-safe.
     */
    private static final class TaskExecutionCsvSink {
        final BufferedWriter writer;
        final int workerCount;
        final ShardedRoutingConfig routing;
        final boolean isShared;

        TaskExecutionCsvSink(BufferedWriter writer,
                             int workerCount,
                             ShardedRoutingConfig routing,
                             boolean isShared) {
            this.writer = writer;
            this.workerCount = workerCount;
            this.routing = routing == null ? ShardedRoutingConfig.defaults() : routing;
            this.isShared = isShared;
        }
    }

    private record PhaseResult(List<Task> retainedTasks,
                               PerKindLatencyRecorder recorder,
                               TailSnapshot tail,
                               int submitted,
                               long submitStartNanos,
                               long submitEndNanos,
                               long submitNanos,
                               long shutdownNanos,
                               long drainNanos,
                               long totalNanos,
                               int backpressureEvents,
                               long backpressureWaitNanos,
                               long nextTaskId) {}

    private record TailTaskSample(long taskId,
                                  WorkloadKind kind,
                                  boolean measurement,
                                  long enqueuedNanos,
                                  long startNanos,
                                  long finishNanos,
                                  long queueWaitNanos,
                                  long executionNanos,
                                  long endToEndNanos) {}

    private record TailSnapshot(int completedInSubmit,
                                int completedInShutdown,
                                int completedInDrain,
                                int notStarted,
                                int notFinished,
                                int finishedAfterDrain,
                                long maxFinishNanos,
                                List<TailTaskSample> topByEndToEnd) {}

    public static void main(String[] args) throws Exception {
        Path configPath = parseConfigPath(args);
        RootConfig root = BenchmarkConfigLoader.load(configPath);
        root.validate();

        System.out.println("=== YAML Benchmark Plan ===");
        System.out.printf("  Config file      : %s%n", configPath);
        System.out.printf("  Runs             : %d%n", root.runs().size());
        System.out.println();

        for (RunConfig run : root.runs()) {
            if (!run.enabled()) {
                System.out.printf("--- Skipping run '%s' (enabled=false) ---%n%n", run.name());
                continue;
            }
            executeRun(root, run);
            System.out.println();
        }
    }

    private static void executeRun(RootConfig root, RunConfig run) throws Exception {
        GlobalConfig global = root.global();
        WorkloadConfig workload = root.workloads().get(run.workload());
        BenchmarkMode mode = BenchmarkMode.fromConfigValue(run.mode());

        if (mode != BenchmarkMode.SHARED && mode != BenchmarkMode.SHARDED && mode != BenchmarkMode.HYBRID) {
            throw new IllegalArgumentException("runs[].mode must be shared|sharded|hybrid for YAML runs: " + run.mode());
        }

        TaskGenerator generator = new TaskGenerator(
                workload, global.seed(), global.workloadSeedMode(), global.workloadSeed());

        // Configure the memory-bound workload's dead-store sink for this
        // run. SHARED_VOLATILE preserves legacy behaviour; THREAD_LOCAL
        // avoids cross-core cache-line invalidation on the blackhole
        // store. Safe to call here (before workers start).
        MemoryBoundWorkload.configureBlackhole(global.blackholeMode());

        // Resolve effective hybrid config (per-run override > root). Required
        // for HYBRID mode; ignored for SHARED/SHARDED.
        HybridConfig effectiveHybrid = run.hybrid() != null ? run.hybrid() : root.hybrid();
        PinningConfig pinning = run.pinning() == null ? PinningConfig.disabled() : run.pinning();
        pinning.validate(run.name(), mode, global.workerCount());

        // Optional diagnostics — supported in SHARDED and SHARED modes.
        // HYBRID is not wired in this revision (kept minimal per spec).
        // Topology mapping:
        //   SHARDED: workerCount = global.workerCount(),  queueCount = same
        //   SHARED : workerCount = 1,                     queueCount = 1
        // The SHARED entry aggregates all pool threads (queueId=0).
        DiagnosticsConfig diagCfg = root.diagnosticsOrDisabled();
        final boolean diagSupported =
                (mode == BenchmarkMode.SHARDED || mode == BenchmarkMode.SHARED) && diagCfg.anyEnabled();
        final int diagWorkerCount = (mode == BenchmarkMode.SHARED) ? 1 : global.workerCount();
        final int diagQueueCount  = diagWorkerCount;
        final DiagnosticsCollector diagnostics = diagSupported
                ? new DiagnosticsCollector(
                        diagCfg,
                        diagWorkerCount, diagQueueCount,
                        global.taskCount() > 0 ? global.taskCount() : 0)
                : null;
        WorkerStats[] workerStats = diagnostics == null ? null : diagnostics.workerStats();

        // Per-worker busy vs. idle tracker. Enabled whenever diagnostics
        // are enabled (top-level flag), independent of workerStats sub-
        // flags — because busy/idle is the primary lens for the
        // "shared vs sharded throughput/latency" investigation.
        // Overhead when null: zero. Overhead when non-null: two
        // System.nanoTime() calls + a few long ops per task (~50 ns
        // total on modern x86; safe for 50 µs workloads).
        final WorkerBusyIdleTracker busyIdle = diagCfg.enabled()
                ? new WorkerBusyIdleTracker(global.workerCount())
                : null;

        // Run directory + optional per-task attribution recorder are
        // created BEFORE the dispatcher so worker threads (especially
        // sharded workers, which start in the executor constructor)
        // see ACTIVE != null and can register their per-thread buffers
        // at startup via ensureBufferForCurrentThread(workerId, shardId).
        Path runDir = Paths.get("results", run.name());
        Files.createDirectories(runDir);

        AttributionConfig attrCfg = root.attributionOrDisabled();
        AttributionRecorder attribution = AttributionRecorder.createIfEnabled(
                attrCfg,
                run.name(),
                mode.name(),
                run.workload(),
                runDir);
        AttributionRecorder.setActive(attribution);

        Dispatcher dispatcher;
        if (mode == BenchmarkMode.SHARDED && (workerStats != null || busyIdle != null)) {
            dispatcher = new ShardedOnlyDispatcher(global.workerCount(), pinning, workerStats,
                    global.shardedRouting(), busyIdle);
        } else if (mode == BenchmarkMode.SHARED && (workerStats != null || busyIdle != null)) {
            // Single-entry stats array — afterExecute() in the shared TPE
            // calls stats[0].onTaskCompleted(task) per measurement task.
            dispatcher = new SharedOnlyDispatcher(global.workerCount(), pinning,
                    workerStats == null ? null : workerStats[0], busyIdle);
        } else {
            dispatcher = createDispatcher(mode, global.workerCount(), effectiveHybrid, pinning,
                    global.shardedRouting());
        }


        ProfilingConfig profiling = root.profiling() == null
                ? new ProfilingConfig(false, "cli", "profile", "beforeMeasurement", "afterMeasurement",
                        "${runName}.jfr", null, null, 200L, 100L, null, null)
                : root.profiling();

        ProfilingSession session = buildSession(profiling, run.name(), runDir);
        Path jfrOutput  = jfrOutputPath(profiling, run.name(), runDir);
        Path perfOutput = perfOutputPath(profiling, run.name(), runDir);
        Path asyncOutput = asyncProfilerOutputPath(profiling, run.name(), runDir);

        String hybridPolicy = mode == BenchmarkMode.HYBRID && effectiveHybrid != null
                ? effectiveHybrid.policyDescription() : "";

        MeasurementContext ctx = new MeasurementContext(
                run.name(), mode.name(), run.workload(), global.workerCount(), hybridPolicy);

        // Optional streaming per-task execution CSV. Opened before
        // measurement phase (warmup is intentionally excluded), closed
        // in the finally block below. When disabled the field stays
        // null and there is zero overhead.
        BufferedWriter taskExecCsvWriter = null;
        TaskExecutionCsvSink taskExecCsvSink = null;
        if (diagCfg.taskExecutionCsv()) {
            Path csvPath = runDir.resolve("task_execution_times.csv");
            taskExecCsvWriter = Files.newBufferedWriter(csvPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            taskExecCsvWriter.write("kind,taskId,shardId,queueWaitNanos,executionNanos,endToEndNanos\n");
            taskExecCsvSink = new TaskExecutionCsvSink(
                    taskExecCsvWriter,
                    global.workerCount(),
                    global.shardedRouting(),
                    mode == BenchmarkMode.SHARED);
        }


        try {
            System.out.println("========================================");
            System.out.printf("Run: %s%n", run.name());
            System.out.println("========================================");
            System.out.printf("  Mode             : %s%n", mode);
            System.out.printf("  Workload         : %s%n", run.workload());
            System.out.println("  Entries          :");
            int idx = 0;
            for (WorkloadEntry e : workload.entries()) {
                if (e.usesFixedCpuIterations()) {
                    System.out.printf(
                            "    [%d] %-20s kind=%-6s cpuIterations=%d ratio=%.4f%n",
                            idx++, e.displayName(), e.kind().name(), e.cpuIterations(), e.ratio());
                } else if (e.usesFixedMemorySteps()) {
                    System.out.printf(
                            "    [%d] %-20s kind=%-6s memorySteps=%d ratio=%.4f%n",
                            idx++, e.displayName(), e.kind().name(), e.memorySteps(), e.ratio());
                } else {
                    System.out.printf(
                            "    [%d] %-20s kind=%-6s targetMillis=%-4d ratio=%.4f%n",
                            idx++, e.displayName(), e.kind().name(), e.targetMillis(), e.ratio());
                }
            }
            System.out.println("  Calibration      :");
            int cidx = 0;
            for (TaskGenerator.Calibration c : generator.calibrations()) {
                System.out.printf("    [%d] %s%n", cidx++, c.summary());
            }
            System.out.printf("  Workers          : %d%n", global.workerCount());
            System.out.printf("  %s%n", pinning.describe());
            System.out.printf("  Max in-flight    : %d%n", global.maxInflight());
            System.out.printf("  Warmup           : %d s%n", global.warmupSeconds());
            System.out.printf("  Measurement      : %d s%n", global.measurementSeconds());
            System.out.printf("  Task count       : %s%n",
                    global.taskCount() > 0 ? String.valueOf(global.taskCount()) : "unlimited (time-based)");
            if (mode == BenchmarkMode.HYBRID) {
                System.out.printf("  Hybrid workers   : shared=%d, sharded=%d%n",
                        effectiveHybrid.sharedWorkers(), effectiveHybrid.shardedWorkers());
                System.out.printf("  Hybrid routing   : %s%n", effectiveHybrid.policyDescription());
                int hybridTotal = effectiveHybrid.sharedWorkers() + effectiveHybrid.shardedWorkers();
                if (hybridTotal != global.workerCount()) {
                    System.err.printf(
                            "  *** WARNING: hybrid.sharedWorkers + hybrid.shardedWorkers (%d) != global.workerCount (%d). "
                                    + "Hybrid will run with %d total workers, which is NOT comparable to shared/sharded "
                                    + "runs at %d workers. Adjust YAML for apples-to-apples comparison.%n",
                            hybridTotal, global.workerCount(), hybridTotal, global.workerCount());
                }
            }
            System.out.println();

            long taskId = 0L;

            // 1. Warmup — profilers are NOT running. No submit-end
            // callback needed; the warmup phase has no observation window.
            PhaseResult warmup = runPhase(dispatcher, generator, global.maxInflight(),
                    global.warmupSeconds() * 1_000_000_000L,
                    false,
                    false,
                    0,
                    taskId,
                    null,
                    null);
            taskId = warmup.nextTaskId();

            System.out.printf("  Warmup done      : %,d tasks in %.3f s (submit) + %.3f s (drain)%n",
                    warmup.submitted(),
                    warmup.submitNanos() / 1_000_000_000.0,
                    warmup.drainNanos()  / 1_000_000_000.0);

            // 2. Start profilers and let startup noise settle.
            session.start();
            session.beforeMeasurement();

            // Queue-depth sampler covers the SUBMIT WINDOW ONLY
            // (submitStart .. submitEnd). It is stopped via the
            // runPhase onSubmitEnd callback below, so the post-submit
            // drain — during which queues empty out — does NOT bias
            // the average toward zero.
            QueueDepthSampler depthSampler = new QueueDepthSampler(dispatcher, 10L);
            depthSampler.start();

            // Optional diagnostics window sampler — opt-in, supported
            // for both SHARDED (per-shard queue probe) and SHARED
            // (single queue probe via SharedExecutor.getQueueSize()).
            // Aligned exactly with the submit window (started here,
            // stopped in the onSubmitEnd callback below).
            if (diagnostics != null) {
                if (dispatcher instanceof ShardedOnlyDispatcher dShardDiag) {
                    diagnostics.startSampler(dShardDiag.executor());
                } else if (dispatcher instanceof SharedOnlyDispatcher dSharedDiag) {
                    SharedExecutor sx = dSharedDiag.executor();
                    diagnostics.startSampler((int q) -> sx.getQueueSize());
                }
            }

            // Per-shard / per-window latency analyzer (SHARDED only).
            // OFF by default; gated by diagnostics.shardLatencyCsv. Hot
            // path is unchanged — all required per-task fields are
            // already on Task. Only a 10 ms-cadence depth sampler runs
            // during the submit window.
            final ShardLatencyAnalyzer shardAnalyzer =
                    (diagCfg.shardLatencyCsv() && dispatcher instanceof ShardedOnlyDispatcher dShardA)
                            ? new ShardLatencyAnalyzer(
                                    global.workerCount(),
                                    global.measurementSeconds() * 1000L)
                            : null;
            if (shardAnalyzer != null) {
                shardAnalyzer.start(((ShardedOnlyDispatcher) dispatcher).executor());
            }

            // 3. Measurement window: SUBMIT WINDOW ONLY.
            //    Bracketed by JFR anchor events at submitStart and
            //    submitEnd. The post-submit drain is excluded from all
            //    profiler / sampler windows so heavy-tail drain costs
            //    cannot pollute the recording.
            // DIAGNOSTIC: per-shard pending queue size at submitEnd /
            // after drain (sharded only). Captured into arrays here so
            // the post-drain block can compare. Empty / null when mode
            // is not SHARDED.
            int[] shardQueueAtSubmitEnd = (dispatcher instanceof ShardedOnlyDispatcher dShardEnd)
                    ? new int[dShardEnd.executor().getWorkerCount()] : null;

            session.markMeasurementStart(ctx);
            // Optional cheap aggregate CPU utilization. One
            // getProcessCpuTime() call at start + one at end of the
            // measurement window (submit + shutdown + drain). Zero
            // hot-path cost. Safely a no-op on non-HotSpot JVMs.
            long cpuTimeStartNs = readProcessCpuTimeNs();
            long wallTimeStartNs = System.nanoTime();
            boolean retentionRequiredByDiagnostics =
                    (mode == BenchmarkMode.SHARDED) && (diagCfg.shardLatencyCsv() || diagCfg.rawTaskLogging());
            boolean effectiveRetainCompletedTasks = global.retainCompletedTasks() || retentionRequiredByDiagnostics;
            PhaseResult measurement = runPhase(dispatcher, generator, global.maxInflight(),
                    global.measurementSeconds() * 1_000_000_000L,
                    true,
                    effectiveRetainCompletedTasks,
                    global.taskCount(),
                    taskId,
                    () -> {
                        // Fired immediately after the last submit and
                        // BEFORE drain — this is what makes the window
                        // "submit only".
                        //
                        // Order matters:
                        //   1. emit JFR measurement-stop anchor
                        //   2. stop the queue-depth sampler
                        //   3. STOP THE PROFILER SESSION ENTIRELY
                        // so async-profiler / perf finalize their
                        // recordings before the dispatcher starts
                        // draining (and emitting drain-tail samples).
                        // ProfilingSession.stop() is idempotent, so the
                        // best-effort stop() in the finally block below
                        // is a safe no-op on the happy path.
                        session.markMeasurementStop(ctx);
                        depthSampler.stop();
                        // Diagnostics sampler — same window as submit.
                        if (diagnostics != null) {
                            diagnostics.stopSampler();
                        }
                        if (shardAnalyzer != null) {
                            shardAnalyzer.stop();
                        }
                        // DIAGNOSTIC: snapshot per-shard queue depth at
                        // submitEnd BEFORE drain consumes them. Read-only
                        // accessor; no timing impact.
                        if (shardQueueAtSubmitEnd != null
                                && dispatcher instanceof ShardedOnlyDispatcher dShardSnap) {
                            ShardedExecutor sx = dShardSnap.executor();
                            for (int i = 0; i < shardQueueAtSubmitEnd.length; i++) {
                                shardQueueAtSubmitEnd[i] = sx.getQueueSize(i);
                            }
                        }
                        try {
                            session.stop();
                        } catch (Exception e) {
                            System.err.println("[profiling] session.stop() failed at submitEnd: " + e);
                        }
                    },
                    taskExecCsvSink);

            // Profilers were already stopped inside the onSubmitEnd
            // callback above (at submitEnd, before drain). Nothing to
            // do here — the finally-block's session.stop() is a no-op
            // on the happy path because ProfilingSession.stop() is
            // idempotent.

            double submitSecs   = measurement.submitNanos()   / 1_000_000_000.0;
            double shutdownSecs = measurement.shutdownNanos() / 1_000_000_000.0;
            double drainSecs    = measurement.drainNanos()    / 1_000_000_000.0;
            double totalSecs    = measurement.totalNanos()    / 1_000_000_000.0;
            double submittedPerSecond = measurement.submitted() / Math.max(submitSecs, 1e-9);
            double completedPerSecond = measurement.submitted() / Math.max(totalSecs,  1e-9);
            double bpWaitMs = measurement.backpressureWaitNanos() / 1_000_000.0;
            double avgDepth = depthSampler.avgQueueDepth();
            int    maxDepth = depthSampler.maxQueueDepth();

            PerKindLatencyRecorder recorder = measurement.recorder();

            if (recorder.recordedTasks() < MIN_USEFUL_SAMPLES) {
                System.err.printf("  *** WARNING: only %d tasks recorded — too few for percentile analysis.%n",
                        recorder.recordedTasks());
            }

            StringBuilder summary = new StringBuilder();
            summary.append("runName=").append(run.name()).append('\n');
            summary.append("mode=").append(run.mode()).append('\n');
            summary.append("workload=").append(run.workload()).append('\n');
            summary.append("workerBudget=").append(global.workerCount()).append('\n');
            summary.append("maxInFlight=").append(global.maxInflight()).append('\n');
            if (mode == BenchmarkMode.HYBRID && effectiveHybrid != null) {
                int hybridTotal = effectiveHybrid.sharedWorkers() + effectiveHybrid.shardedWorkers();
                summary.append("hybridTotalWorkers=").append(hybridTotal).append('\n');
                summary.append("hybridSharedWorkers=").append(effectiveHybrid.sharedWorkers()).append('\n');
                summary.append("hybridShardedWorkers=").append(effectiveHybrid.shardedWorkers()).append('\n');
                summary.append("hybridRouting=").append(effectiveHybrid.policyDescription()).append('\n');
            }
            appendWorkloadSummary(summary, workload);
            appendCalibrationSummary(summary, generator);
            summary.append("pinningEnabled=").append(pinning.enabled()).append('\n');
            summary.append("pinningCoreMap=")
                    .append(pinning.coreMap() == null ? "null" : java.util.Arrays.toString(pinning.coreMap()))
                    .append('\n');
            // Artifact-isolation knobs (see ShardedRoutingConfig,
            // WorkloadSeedMode, BlackholeMode). Defaults preserve the
            // legacy behaviour; non-default values flag an A/B run.
            summary.append("shardedRouting.mode=").append(global.shardedRouting().mode()).append('\n');
            summary.append("shardedRouting.seed=").append(global.shardedRouting().routingSeed()).append('\n');
            summary.append("workloadSeedMode=").append(global.workloadSeedMode()).append('\n');
            summary.append("workloadSeed=").append(global.workloadSeed()).append('\n');
            summary.append("blackholeMode=").append(global.blackholeMode()).append('\n');
            summary.append("retainCompletedTasks=").append(effectiveRetainCompletedTasks).append('\n');
            summary.append("retainCompletedTasks.configured=").append(global.retainCompletedTasks()).append('\n');
            summary.append("submitted=").append(measurement.submitted()).append('\n');
            summary.append("submitDurationSeconds=").append(String.format("%.3f", submitSecs)).append('\n');
            // taskDrainDurationSeconds: time spent waiting for in-flight
            //   workload tasks to finish AFTER profiler shutdown returned.
            // shutdownDurationSeconds:   time spent inside session.stop()
            //   (perf SIGINT + waitFor, async-profiler stop, JFR.stop).
            // drainDurationSeconds (legacy): sum of the two above, kept
            //   for backward compatibility with existing analysis scripts.
            summary.append("taskDrainDurationSeconds=").append(String.format("%.3f", drainSecs)).append('\n');
            summary.append("shutdownDurationSeconds=").append(String.format("%.3f", shutdownSecs)).append('\n');
            summary.append("drainDurationSeconds=").append(String.format("%.3f", drainSecs + shutdownSecs)).append('\n');
            summary.append("totalDurationSeconds=").append(String.format("%.3f", totalSecs)).append('\n');
            summary.append("submittedPerSecond=").append(String.format("%.1f", submittedPerSecond)).append('\n');
            summary.append("completedPerSecond=").append(String.format("%.1f", completedPerSecond)).append('\n');
            summary.append("backpressureEvents=").append(measurement.backpressureEvents()).append('\n');
            summary.append("backpressureWaitMillis=").append(String.format("%.3f", bpWaitMs)).append('\n');
            summary.append("avgQueueDepth=").append(String.format("%.2f", avgDepth)).append('\n');
            summary.append("maxQueueDepth=").append(maxDepth).append('\n');
            summary.append("queueDepthSamples=").append(depthSampler.sampleCount()).append("\n\n");
            summary.append(recorder.summary()).append('\n');

            // -- Greppable per-CPU-entry cpuIterations marker --
            // Emitted whether the count came from calibration or from a
            // YAML-fixed cpuIterations entry, so analysis scripts can
            // pull "cpuIterations=" uniformly across runs.
            for (TaskGenerator.Calibration c : generator.calibrations()) {
                if (c.kind() == WorkloadKind.CPU) {
                    summary.append("cpuIterations[").append(c.name()).append("]=")
                            .append(c.cpuIterations())
                            .append(c.fixedCpuIterations() ? "  (fixed)" : "  (calibrated)")
                            .append('\n');
                }
            }

            // -- Overall executionMs summary lines --
            LatencyRecorder overall = recorder.overall();
            summary.append(String.format("executionMs.avg=%.3f%n", overall.avgExecution() / 1_000_000.0));
            summary.append(String.format("executionMs.p50=%.3f%n", overall.p50Execution() / 1_000_000.0));
            summary.append(String.format("executionMs.p95=%.3f%n", overall.p95Execution() / 1_000_000.0));
            summary.append(String.format("executionMs.p99=%.3f%n", overall.p99Execution() / 1_000_000.0));

            summary.append(recorder.compactByKind());

            // Tail / drain diagnostic — emitted to BOTH summary_sharded50.txt and
            // stdout, byte-identical formatting via appendTailDiagnostics.
            // Placed AFTER the standard latency summary and BEFORE the
            // per-worker / queue-depth diagnostics sections so the
            // ordering in summary_sharded50.txt mirrors what an operator sees on
            // the console.
            StringBuilder tailDiag = new StringBuilder();
            appendTailDiagnostics(tailDiag, measurement, dispatcher, shardQueueAtSubmitEnd);
            summary.append(tailDiag);

            // ---- Extended latency summary (mean + p50/p95/p99 for
            //      queue-wait and end-to-end). p99 is already reported
            //      elsewhere; this consolidates the four numbers per
            //      metric for shared vs. sharded comparison. ----
            summary.append('\n').append("=== Latency: overall averages ===").append('\n');
            summary.append(String.format("queueWaitMs.avg=%.3f%n",  overall.avgQueueWait()  / 1_000_000.0));
            summary.append(String.format("queueWaitMs.p50=%.3f%n",  overall.p50QueueWait()  / 1_000_000.0));
            summary.append(String.format("queueWaitMs.p95=%.3f%n",  overall.p95QueueWait()  / 1_000_000.0));
            summary.append(String.format("queueWaitMs.p99=%.3f%n",  overall.p99QueueWait()  / 1_000_000.0));
            summary.append(String.format("endToEndMs.avg=%.3f%n",   overall.avgEndToEnd()   / 1_000_000.0));
            summary.append(String.format("endToEndMs.p50=%.3f%n",   overall.p50EndToEnd()   / 1_000_000.0));
            summary.append(String.format("endToEndMs.p95=%.3f%n",   overall.p95EndToEnd()   / 1_000_000.0));
            summary.append(String.format("endToEndMs.p99=%.3f%n",   overall.p99EndToEnd()   / 1_000_000.0));

            // ---- Benchmark completion accounting (explicit) ----
            // completedTasks:
            //   SHARDED = sum of ShardedExecutor.getProcessedCounts() (all phases)
            //   SHARED  = SharedExecutor.getCompletedTaskCount()      (all phases)
            //   Includes warmup + measurement (executor lifetime).
            long completedTasksAllPhases = -1L;
            if (dispatcher instanceof ShardedOnlyDispatcher dS) {
                long sum = 0L;
                for (long v : dS.executor().getProcessedCounts()) sum += v;
                completedTasksAllPhases = sum;
            } else if (dispatcher instanceof SharedOnlyDispatcher dSh) {
                completedTasksAllPhases = dSh.executor().getCompletedTaskCount();
            }
            long cpuTimeEndNs = readProcessCpuTimeNs();
            long wallTimeEndNs = System.nanoTime();
            long wallSpanNs = wallTimeEndNs - wallTimeStartNs;
            long cpuSpanNs  = (cpuTimeStartNs >= 0 && cpuTimeEndNs >= 0)
                    ? (cpuTimeEndNs - cpuTimeStartNs) : -1L;
            int  cores      = Runtime.getRuntime().availableProcessors();
            double procCpuUtilization = (cpuSpanNs < 0 || wallSpanNs <= 0)
                    ? Double.NaN
                    : (double) cpuSpanNs / ((double) wallSpanNs * cores);

            summary.append('\n').append("=== Benchmark Completion ===").append('\n');
            summary.append(String.format("completion.submittedTasks=%d%n", measurement.submitted()));
            summary.append(String.format("completion.completedTasksAllPhases=%d%n", completedTasksAllPhases));
            summary.append(String.format("completion.submitDurationSeconds=%.6f%n", submitSecs));
            summary.append(String.format("completion.shutdownDurationSeconds=%.6f%n", shutdownSecs));
            summary.append(String.format("completion.taskDrainDurationSeconds=%.6f%n", drainSecs));
            summary.append(String.format("completion.totalDurationSeconds=%.6f%n", totalSecs));
            summary.append(String.format("completion.wallClockWindowSeconds=%.6f%n",
                    wallSpanNs / 1_000_000_000.0));
            summary.append("completion.throughputFormula=submitted / totalDurationSeconds " +
                    "(= submitted / (submit + shutdown + drain))\n");
            summary.append(String.format("completion.submittedPerSecond=%.1f%n", submittedPerSecond));
            summary.append(String.format("completion.completedPerSecond=%.1f%n", completedPerSecond));
            if (Double.isNaN(procCpuUtilization)) {
                summary.append("completion.processCpuUtilization=unavailable\n");
            } else {
                summary.append(String.format(
                        "completion.processCpuUtilization=%.4f  (cpuTimeNs=%d, wallNs=%d, cores=%d)%n",
                        procCpuUtilization, cpuSpanNs, wallSpanNs, cores));
            }

            // ---- Per-worker busy/idle block (both SHARED and SHARDED) ----
            if (busyIdle != null) {
                busyIdle.appendSummary(summary);
            }

            // ---- Aggregate perf stat, per-task normalized (only when
            //      profiling.perf.enablePerfStat=true). Reads the file
            //      that PerfStatProfiler already produced, divides raw
            //      counts by measurement submitted count, and appends a
            //      block to summary. Uses the existing perf stat file
            //      as sole source; no new abstraction, no new subprocess.
            if (profiling != null && profiling.perf() != null && profiling.perf().perfStatEnabled()) {
                Path perfStatPath = runDir.resolve(
                        profiling.perf().perfStatFilenameOrDefault().replace("${runName}", run.name()));
                appendPerfStatPerTask(summary, perfStatPath, measurement.submitted());
            }

            // Optional diagnostics block — SHARDED and SHARED, opt-in
            // via diagnostics.enabled.
            //
            // Ordering (SHARDED): Task.runWithBeforeComplete(stats)
            // records into WorkerStats BEFORE the in-flight permit is
            // released, so by the time runPhase's drain returns every
            // measurement task's diagnostics update has already
            // happened.
            //
            // Ordering (SHARED): SharedExecutor's
            // ThreadPoolExecutor.afterExecute() hook updates the
            // single aggregate WorkerStats AFTER task.run() returns —
            // which is AFTER the permit release in Task.run()'s
            // finally. The diagnostics may therefore lag the drain by
            // up to poolSize tasks; this is statistically negligible
            // for correlation reports and acceptable for SHARED's
            // "single aggregate" view.
            //
            // publishFinalCounts() flushes the volatile snapshot field
            // amortised at 1 store per 1024 tasks; the trailing
            // < 1024 tasks per worker need this explicit flush.
            if (diagnostics != null) {
                diagnostics.publishFinalCounts();
                diagnostics.appendSummary(summary);
            }

            // Per-shard / per-window CSVs (SHARDED only). Best-effort:
            // logged on failure but never fails the benchmark.
            if (shardAnalyzer != null) {
                try {
                    if (measurement.retainedTasks() == null) {
                        throw new IllegalStateException("shardLatencyCsv/rawTaskLogging requires retained tasks");
                    }
                    shardAnalyzer.analyzeAndWrite(
                            runDir,
                            measurement.retainedTasks(),
                            global.workerCount(),
                            measurement.submitStartNanos(),
                            measurement.submitEndNanos(),
                            diagCfg.shardWindowMillis(),
                            diagCfg.shardLatencyCsv(),   // 3 aggregate CSVs
                            diagCfg.rawTaskLogging(),    // per_task.csv
                            global.shardedRouting(),
                            pinning.enabled() ? pinning.coreMap() : null);
                    if (diagCfg.shardLatencyCsv()) {
                        System.out.printf("  shard CSVs       : %s, %s, %s%n",
                                runDir.resolve("per_shard_latency.csv"),
                                runDir.resolve("per_window_shard_latency.csv"),
                                runDir.resolve("shard_window_correlation.csv"));
                    }
                    if (diagCfg.rawTaskLogging()) {
                        System.out.printf("  per-task CSV     : %s%n",
                                runDir.resolve("per_task.csv"));
                    }
                } catch (Exception e) {
                    System.err.println("[diagnostics] ShardLatencyAnalyzer failed: " + e);
                }
            }

            if (diagCfg.taskExecutionCsv()) {
                System.out.printf("  task-exec CSV    : %s%n",
                        runDir.resolve("task_execution_times.csv"));
            }

            Path summaryPath = runDir.resolve("summary_" + mode.name().toLowerCase() + ".txt");
            Files.writeString(summaryPath, summary.toString());

            System.out.printf("  Measurement done : %,d tasks%n", measurement.submitted());
            System.out.printf("  Submit duration  : %.3f s  (%,.1f tasks/s submitted)%n",
                    submitSecs, submittedPerSecond);
            System.out.printf("  Shutdown duration: %.3f s  (session.stop(): perf/JFR/asprof finalize)%n", shutdownSecs);
            System.out.printf("  Task drain       : %.3f s  (in-flight workload tasks after shutdown)%n", drainSecs);
            System.out.printf("  Total duration   : %.3f s  (%,.1f tasks/s completed)%n",
                    totalSecs, completedPerSecond);
            System.out.printf("  Backpressure     : %,d events, %.3f ms total wait%n",
                    measurement.backpressureEvents(), bpWaitMs);
            System.out.printf("  Queue depth      : avg=%.2f, max=%d  (%d samples @ 10 ms)%n",
                    avgDepth, maxDepth, depthSampler.sampleCount());
            System.out.println();
            System.out.println(recorder.summary());
            System.out.printf("  Summary file     : %s%n", summaryPath);
            if (jfrOutput != null) {
                System.out.printf("  JFR file         : %s%n", jfrOutput);
            }
            if (perfOutput != null) {
                boolean exists = Files.exists(perfOutput);
                long sizeKB = exists ? (Files.size(perfOutput) / 1024) : 0;
                System.out.printf("  perf file        : %s  (%s%s)%n",
                        perfOutput,
                        exists ? "exists" : "MISSING — perf failed to start; see .perf.log",
                        exists ? ", " + sizeKB + " KiB" : "");
                Path perfLog = runDir.resolve(run.name() + ".perf.log");
                if (Files.exists(perfLog)) {
                    System.out.printf("  perf log         : %s%n", perfLog);
                }
            } else {
                System.out.println("  perf             : disabled (profiling.perf.enabled=false or non-Linux host)");
            }
            if (asyncOutput != null) {
                boolean exists = Files.exists(asyncOutput);
                long sizeKB = exists ? (Files.size(asyncOutput) / 1024) : 0;
                System.out.printf("  async-profiler   : %s  (%s%s)%n",
                        asyncOutput,
                        exists ? "exists" : "MISSING — asprof failed; see .async.log",
                        exists ? ", " + sizeKB + " KiB" : "");
            }

            // 5. Reproducibility metadata (outside the measurement window).
            writeRunMetadata(runDir, run, global, mode, profiling, effectiveHybrid,
                    submittedPerSecond, completedPerSecond,
                    measurement.submitted(), submitSecs, drainSecs, shutdownSecs, totalSecs,
                    avgDepth, maxDepth,
                    effectiveRetainCompletedTasks,
                    jfrOutput, perfOutput, asyncOutput);

            // 6. Diagnostic: per-shard queue distribution (sharded mode only).
            //    Pure stdout output — does not affect timing, latency, or
            //    queue behavior. Runs after all metrics are finalized and
            //    before dispatcher shutdown in the finally block.
            if (dispatcher instanceof ShardedOnlyDispatcher d) {
                d.executor().printQueueDistribution();
            }

            // 7. DIAGNOSTIC: tail-latency / drain-accounting sanity check.
            //    Same StringBuilder that was appended to summary_sharded50.txt
            //    above — single formatting source, so console and file
            //    are byte-identical. Pure observation; touches no
            //    executor, queue, or timing field.
            System.out.print(tailDiag);
        } finally {
            try {
                session.stop();
            } catch (Exception ignore) {
                /* best-effort on failure path */
            } finally {
                if (taskExecCsvWriter != null) {
                    try {
                        synchronized (taskExecCsvWriter) {
                            taskExecCsvWriter.flush();
                            taskExecCsvWriter.close();
                        }
                    } catch (IOException ignore) {
                        /* best-effort */
                    }
                }

                // 1. Stop accepting new tasks and 2. wait for in-flight
                //    workers to finish so no thread is still calling
                //    AttributionRecorder.record(...) when we flush.
                dispatcher.shutdown();
                boolean terminated = dispatcher.awaitTermination(30, TimeUnit.SECONDS);

                // 3. Detach the recorder (no further record() can succeed
                //    against this run's recorder) BEFORE flushing.
                // 4. Flush per-worker buffers to CSV — but only if the
                //    dispatcher actually terminated. If it did not, some
                //    worker may still be writing into its local buffer,
                //    and flushing would race with concurrent writes.
                if (attribution != null) {
                    AttributionRecorder.setActive(null);
                    if (!terminated) {
                        System.err.println(
                                "[attribution] WARNING: dispatcher did not terminate within 30s before "
                                + "attribution flush; skipping attribution flush to avoid racing with "
                                + "active worker writes.");
                    } else {
                        try {
                            long recorded     = attribution.flushAndClose();
                            long dropped      = attribution.droppedRecords();
                            long missing      = attribution.missingBufferCount();
                            long perfFailed   = attribution.perfReadFailed();
                            long unmatched    = attribution.unmatchedPreSamples();
                            System.out.printf(
                                    "  attribution CSV  : %s  (%,d sampled, %,d dropped, %,d missingBuffer, 1-in-%d row, 1-in-%d perf)%n",
                                    attribution.csvPath(),
                                    recorded,
                                    dropped,
                                    missing,
                                    attribution.sampleInterval(),
                                    attribution.perfStride());
                            if (perfFailed > 0 || unmatched > 0) {
                                System.out.printf(
                                        "  attribution perf : %,d rows had no usable counter data, %,d unmatched pre-samples%n",
                                        perfFailed, unmatched);
                            }
                            if (dropped > 0) {
                                System.err.printf(
                                        "  *** WARNING: %,d sampled attribution record(s) were dropped "
                                                + "because a per-worker buffer was full. "
                                                + "Increase attribution.bufferCapacityPerWorker.%n",
                                        dropped);
                            }
                            if (missing > 0) {
                                System.err.printf(
                                        "  *** WARNING: %,d sampled attribution record(s) were dropped "
                                                + "because the calling worker thread had no registered "
                                                + "buffer. This indicates instrumentation misconfiguration: "
                                                + "some worker did not call "
                                                + "AttributionRecorder.ensureBufferForCurrentThread(...) "
                                                + "at startup. Treat the attribution result as suspect.%n",
                                        missing);
                            }
                        } catch (IOException e) {
                            System.err.println("[attribution] flush failed: " + e);
                        }
                    }
                }
            }
        }
    }

    /* ================================================================
     *  Profiling session wiring
     * ================================================================ */

    private static ProfilingSession buildSession(ProfilingConfig profiling,
                                                 String runName,
                                                 Path runDir) {
        if (profiling == null || !profiling.enabled()) {
            return ProfilingSession.disabled();
        }
        List<Profiler> children = new ArrayList<>(3);

        JfrProfiler.Control ctrl = "api".equalsIgnoreCase(profiling.control())
                ? JfrProfiler.Control.API : JfrProfiler.Control.CLI;
        Path jfrOut = runDir.resolve(profiling.filename().replace("${runName}", runName));
        children.add(new JfrProfiler(ctrl, runName, profiling.settings(), jfrOut,
                profiling.startCommand(), profiling.stopCommand(),
                profiling.captureLocks(), profiling.lockEventThreshold()));

        PerfConfig perf = profiling.perf();
        if (perf != null && perf.enabled()) {
            // Insert perf FIRST in start order so its window brackets JFR.
            // CompositeProfiler stops in reverse order, so perf stops LAST —
            // which is what we want for clean timeline alignment.
            children.add(0, new PerfProfiler(perf, runName, runDir));
        }

        // Independent `perf stat` aggregate counters. Off by default; produces
        // <runName>.perf.stat.txt. Inserted at the front so it brackets JFR
        // like perf record does. Independent of perf record's enabled flag.
        if (perf != null && perf.perfStatEnabled()) {
            children.add(0, new com.scott.profiling.PerfStatProfiler(perf, runName, runDir));
        }

        AsyncProfilerConfig async = profiling.asyncProfiler();
        if (async != null && async.enabled()) {
            // Append LAST: start order = [perf, jfr, async]; stop order =
            // [async, jfr, perf]. async-profiler's window therefore sits
            // strictly inside both JFR and perf, so its samples are always
            // bracketed by the JFR measurement anchors used for alignment.
            children.add(new AsyncProfilerProfiler(async, runName, runDir));
        }

        Profiler rootProfiler = children.size() == 1 ? children.get(0) : new CompositeProfiler(children);
        return new ProfilingSession(rootProfiler,
                profiling.startupQuietPeriodMs(),
                profiling.shutdownFlushMs());
    }

    private static Path jfrOutputPath(ProfilingConfig p, String runName, Path runDir) {
        if (p == null || !p.enabled()) return null;
        return runDir.resolve(p.filename().replace("${runName}", runName));
    }

    private static Path perfOutputPath(ProfilingConfig p, String runName, Path runDir) {
        if (p == null || !p.enabled() || p.perf() == null || !p.perf().enabled()) return null;
        return runDir.resolve(p.perf().filenameOrDefault().replace("${runName}", runName));
    }

    private static Path asyncProfilerOutputPath(ProfilingConfig p, String runName, Path runDir) {
        if (p == null || !p.enabled() || p.asyncProfiler() == null || !p.asyncProfiler().enabled()) return null;
        return runDir.resolve(p.asyncProfiler().filenameOrDefault().replace("${runName}", runName));
    }

    private static void writeRunMetadata(Path runDir,
                                         RunConfig run,
                                         GlobalConfig global,
                                         BenchmarkMode mode,
                                         ProfilingConfig profiling,
                                         HybridConfig hybrid,
                                         double submittedPerSecond,
                                         double completedPerSecond,
                                         int submitted,
                                         double submitSecs,
                                         double drainSecs,
                                         double shutdownSecs,
                                         double totalSecs,
                                         double avgQueueDepth,
                                         int maxQueueDepth,
                                         boolean effectiveRetainCompletedTasks,
                                         Path jfrOutput,
                                         Path perfOutput,
                                         Path asyncOutput) throws java.io.IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mode",                 mode.name());
        fields.put("workload",             run.workload());
        fields.put("workerBudget",         global.workerCount());
        fields.put("workerCount",          global.workerCount());
        fields.put("maxInflight",          global.maxInflight());
        fields.put("warmupSeconds",        global.warmupSeconds());
        fields.put("measurementSeconds",   global.measurementSeconds());
        fields.put("taskCount",            global.taskCount());
        fields.put("shardedRouting.mode",  global.shardedRouting().mode().name());
        fields.put("shardedRouting.seed",  global.shardedRouting().routingSeed());
        fields.put("workloadSeedMode",     global.workloadSeedMode().name());
        fields.put("workloadSeed",         global.workloadSeed());
        fields.put("blackholeMode",        global.blackholeMode().name());
        fields.put("retainCompletedTasks", effectiveRetainCompletedTasks);
        fields.put("retainCompletedTasks.configured", global.retainCompletedTasks());
        fields.put("submitted",            submitted);
        fields.put("submitDurationSeconds",     String.format("%.3f", submitSecs));
        // taskDrainDurationSeconds — workload completion AFTER profiler stop.
        // shutdownDurationSeconds  — perf/JFR/async-profiler finalize time.
        // drainDurationSeconds     — legacy sum, kept for back-compat.
        fields.put("taskDrainDurationSeconds",  String.format("%.3f", drainSecs));
        fields.put("shutdownDurationSeconds",   String.format("%.3f", shutdownSecs));
        fields.put("drainDurationSeconds",      String.format("%.3f", drainSecs + shutdownSecs));
        fields.put("totalDurationSeconds",      String.format("%.3f", totalSecs));
        fields.put("submittedPerSecond",   String.format("%.1f", submittedPerSecond));
        fields.put("completedPerSecond",   String.format("%.1f", completedPerSecond));
        fields.put("avgQueueDepth",        String.format("%.2f", avgQueueDepth));
        fields.put("maxQueueDepth",        maxQueueDepth);
        if (mode == BenchmarkMode.HYBRID && hybrid != null) {
            fields.put("hybridTotalWorkers",   hybrid.sharedWorkers() + hybrid.shardedWorkers());
            fields.put("hybridSharedWorkers",  hybrid.sharedWorkers());
            fields.put("hybridShardedWorkers", hybrid.shardedWorkers());
            fields.put("hybridRouting",        hybrid.policyDescription());
        }
        fields.put("profilingEnabled",     profiling != null && profiling.enabled());
        fields.put("profilingControl",     profiling == null ? "" : profiling.control());
        fields.put("jfrSettings",          profiling == null ? "" : profiling.settings());
        fields.put("jfrOutput",            jfrOutput == null ? "" : jfrOutput.toString());
        if (profiling != null && profiling.perf() != null && profiling.perf().enabled()) {
            PerfConfig pc = profiling.perf();
            fields.put("perfEnabled",      true);
            fields.put("perfFrequency",    pc.frequencyOrDefault());
            fields.put("perfCallGraph",    pc.callGraphOrDefault());
            fields.put("perfClock",        pc.clockOrDefault());
            fields.put("perfOutput",       perfOutput == null ? "" : perfOutput.toString());
        } else {
            fields.put("perfEnabled",      false);
        }
        if (profiling != null && profiling.asyncProfiler() != null && profiling.asyncProfiler().enabled()) {
            AsyncProfilerConfig ac = profiling.asyncProfiler();
            fields.put("asyncProfilerEnabled",  true);
            fields.put("asyncProfilerEvent",    ac.eventOrDefault());
            fields.put("asyncProfilerInterval", ac.intervalOrDefault());
            fields.put("asyncProfilerFormat",   ac.formatOrDefault());
            fields.put("asyncProfilerOutput",   asyncOutput == null ? "" : asyncOutput.toString());
        } else {
            fields.put("asyncProfilerEnabled", false);
        }
        Path meta = RunMetadataWriter.write(runDir, run.name(), fields);
        System.out.printf("  Metadata file    : %s%n", meta);
    }

    /**
     * Online measurement aggregator used by runPhase() to avoid
     * retaining every completed Task object by default.
     */
    private static final class OnlineMeasurementCollector implements Task.CompletionObserver {
        private static final int CHUNK_BITS = 16;
        private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
        private static final int CHUNK_MASK = CHUNK_SIZE - 1;
        private static final int TOP_N = 10;

        private final List<long[]> soChunks = new ArrayList<>();
        private final List<long[]> qwChunks = new ArrayList<>();
        private final List<long[]> exChunks = new ArrayList<>();
        private final List<long[]> e2eChunks = new ArrayList<>();
        private final List<byte[]> kindChunks = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);

        private final Object topLock = new Object();
        private final java.util.PriorityQueue<TailTaskSample> topN =
                new java.util.PriorityQueue<>(TOP_N + 1,
                        java.util.Comparator.comparingLong(TailTaskSample::endToEndNanos));

        private final java.util.concurrent.atomic.LongAdder completedInSubmit = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder completedInShutdown = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder completedInDrain = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder notStarted = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder notFinished = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder finishedAfterDrain = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.AtomicLong maxFinishNanos = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

        private volatile long submitEndNanos = Long.MAX_VALUE;
        private volatile long shutdownEndNanos = Long.MAX_VALUE;
        private volatile long drainEndNanos = Long.MAX_VALUE;
        private final TaskExecutionCsvSink csvSink;

        OnlineMeasurementCollector(int expectedTasks) {
            this(expectedTasks, null);
        }

        OnlineMeasurementCollector(int expectedTasks, TaskExecutionCsvSink csvSink) {
            int chunks = Math.max(1, (expectedTasks + CHUNK_SIZE - 1) >>> CHUNK_BITS);
            for (int i = 0; i < chunks; i++) {
                appendChunk();
            }
            this.csvSink = csvSink;
        }

        void setSubmitEndNanos(long ns) {
            this.submitEndNanos = ns;
        }

        void setShutdownEndNanos(long ns) {
            this.shutdownEndNanos = ns;
        }

        void setDrainEndNanos(long ns) {
            this.drainEndNanos = ns;
        }

        @Override
        public void onTaskCompleted(Task t) {
            long s = t.startNanos();
            long f = t.finishNanos();
            if (s == 0L) {
                notStarted.increment();
                return;
            }
            if (f == 0L) {
                notFinished.increment();
                return;
            }

            if (f <= submitEndNanos) {
                completedInSubmit.increment();
            } else if (f <= shutdownEndNanos) {
                completedInShutdown.increment();
            } else {
                completedInDrain.increment();
            }
            if (f > drainEndNanos) {
                finishedAfterDrain.increment();
            }
            maxFinishNanos.accumulateAndGet(f, Math::max);

            int idx = count.getAndIncrement();
            ensureChunk(idx >>> CHUNK_BITS);
            int c = idx >>> CHUNK_BITS;
            int o = idx & CHUNK_MASK;
            soChunks.get(c)[o] = t.submitOverheadNanos();
            qwChunks.get(c)[o] = t.queueWaitTimeNanos();
            exChunks.get(c)[o] = t.executionTimeNanos();
            e2eChunks.get(c)[o] = t.endToEndLatencyNanos();
            kindChunks.get(c)[o] = (byte) t.workloadKind().ordinal();

            long e2e = t.endToEndLatencyNanos();
            synchronized (topLock) {
                if (topN.size() < TOP_N) {
                    topN.add(sampleOf(t));
                } else if (e2e > topN.peek().endToEndNanos()) {
                    topN.poll();
                    topN.add(sampleOf(t));
                }
            }

            if (csvSink != null) {
                writeCsvRow(t);
            }
        }

        private void writeCsvRow(Task t) {
            int shardId = csvSink.isShared
                    ? -1
                    : Hashing.shardOf(t.taskId(), csvSink.workerCount, csvSink.routing);
            long qw = t.queueWaitTimeNanos();
            long ex = t.executionTimeNanos();
            long e2eN = t.endToEndLatencyNanos();
            StringBuilder sb = new StringBuilder(64);
            sb.append(t.workloadKind().name()).append(',')
              .append(t.taskId()).append(',')
              .append(shardId).append(',')
              .append(qw).append(',')
              .append(ex).append(',')
              .append(e2eN).append('\n');
            try {
                synchronized (csvSink.writer) {
                    csvSink.writer.write(sb.toString());
                }
            } catch (IOException ignore) {
                // Diagnostics-only path — never fail the benchmark.
            }
        }

        private TailTaskSample sampleOf(Task t) {
            return new TailTaskSample(
                    t.taskId(),
                    t.workloadKind(),
                    t.isMeasurement(),
                    t.enqueuedNanos(),
                    t.startNanos(),
                    t.finishNanos(),
                    t.queueWaitTimeNanos(),
                    t.executionTimeNanos(),
                    t.endToEndLatencyNanos());
        }

        private synchronized void ensureChunk(int chunkIndex) {
            while (chunkIndex >= soChunks.size()) {
                appendChunk();
            }
        }

        private void appendChunk() {
            soChunks.add(new long[CHUNK_SIZE]);
            qwChunks.add(new long[CHUNK_SIZE]);
            exChunks.add(new long[CHUNK_SIZE]);
            e2eChunks.add(new long[CHUNK_SIZE]);
            kindChunks.add(new byte[CHUNK_SIZE]);
        }

        PerKindLatencyRecorder buildRecorder() {
            int n = count.get();
            PerKindLatencyRecorder out = new PerKindLatencyRecorder(Math.max(n, 1));
            WorkloadKind[] kinds = WorkloadKind.values();
            for (int i = 0; i < n; i++) {
                int c = i >>> CHUNK_BITS;
                int o = i & CHUNK_MASK;
                out.recordRaw(
                        kinds[kindChunks.get(c)[o]],
                        soChunks.get(c)[o],
                        qwChunks.get(c)[o],
                        exChunks.get(c)[o],
                        e2eChunks.get(c)[o]);
            }
            return out;
        }

        TailSnapshot buildTailSnapshot() {
            List<TailTaskSample> sortedTop;
            synchronized (topLock) {
                sortedTop = new ArrayList<>(topN);
            }
            sortedTop.sort((a, b) -> Long.compare(b.endToEndNanos(), a.endToEndNanos()));

            long maxFinish = maxFinishNanos.get();
            if (maxFinish == Long.MIN_VALUE) {
                maxFinish = Long.MIN_VALUE;
            }
            return new TailSnapshot(
                    completedInSubmit.intValue(),
                    completedInShutdown.intValue(),
                    completedInDrain.intValue(),
                    notStarted.intValue(),
                    notFinished.intValue(),
                    finishedAfterDrain.intValue(),
                    maxFinish,
                    sortedTop);
        }
    }

    /* ================================================================
     *  Phase execution with bounded in-flight submission
     * ================================================================ */

    private static PhaseResult runPhase(Dispatcher dispatcher,
                                        TaskGenerator generator,
                                        int maxInflight,
                                        long durationNanos,
                                        boolean measurement,
                                        boolean retainCompletedTasks,
                                        int taskLimit,
                                        long startTaskId,
                                        Runnable onSubmitEnd,
                                        TaskExecutionCsvSink csvSink) throws InterruptedException {
        Semaphore permits = new Semaphore(maxInflight);
        Runnable releasePermit = permits::release;

        long submitStart = System.nanoTime();
        long deadline = submitStart + durationNanos;

        int submitted = 0;
        int backpressure = 0;
        long backpressureWaitNanos = 0L;
        long taskId = startTaskId;

        // Optional full task retention for heavy diagnostics. Default is
        // non-retaining mode (online aggregation only).
        int initialCapacity = (measurement && retainCompletedTasks)
                ? (taskLimit > 0 ? taskLimit : 4096)
                : 0;
        List<Task> tasks = (measurement && retainCompletedTasks)
                ? new ArrayList<>(initialCapacity)
                : null;

        OnlineMeasurementCollector collector = measurement
                ? new OnlineMeasurementCollector(taskLimit > 0 ? taskLimit : 1024, csvSink)
                : null;

        while ((taskLimit > 0 ? submitted < taskLimit : System.nanoTime() < deadline)) {
            if (!permits.tryAcquire()) {
                backpressure++;
                long waitStart = System.nanoTime();
                permits.acquire();
                backpressureWaitNanos += System.nanoTime() - waitStart;
            }

            Task task = generator.nextTask(taskId,
                    measurement,
                    releasePermit,
                    collector);
            dispatcher.submit(task);
            if (tasks != null) {
                tasks.add(task);
            }

            taskId++;
            submitted++;
        }

        long submitEnd = System.nanoTime();
        if (collector != null) {
            collector.setSubmitEndNanos(submitEnd);
        }

        // Fire the callback BEFORE the drain so callers can close their
        // observation windows (profiler anchors, queue-depth sampler)
        // exactly at submitEnd. Drain time is therefore NOT included in
        // any external measurement window.
        //
        // NOTE: session.stop() (perf SIGINT + waitFor, async-profiler
        // stop, JFR.stop) can take many seconds. We measure that time
        // SEPARATELY as `shutdownNanos` so it is not mis-reported as
        // workload drain. Workers continue running tasks concurrently
        // with the callback, so any tasks still in flight at submitEnd
        // typically finish during the callback window.
        long callbackStart = submitEnd;
        if (onSubmitEnd != null) {
            onSubmitEnd.run();
        }
        long callbackEnd = System.nanoTime();
        if (collector != null) {
            collector.setShutdownEndNanos(callbackEnd);
        }

        // Drain: wait for all in-flight tasks to release their permits.
        // This is bookkeeping only — no profiler / sampler observes it.
        permits.acquire(maxInflight);
        permits.release(maxInflight);

        long allDoneEnd = System.nanoTime();
        long submitNanos   = submitEnd - submitStart;
        long shutdownNanos = callbackEnd - callbackStart;
        // taskDrainNanos = time spent waiting on workers AFTER the
        // profiler shutdown callback returned. Almost always tiny when
        // maxInflight ≤ workerCount because all in-flight tasks finish
        // during the (typically multi-second) shutdown window.
        long drainNanos  = allDoneEnd - callbackEnd;
        long totalNanos  = allDoneEnd - submitStart;
        if (collector != null) {
            collector.setDrainEndNanos(allDoneEnd);
        }

        PerKindLatencyRecorder recorder = measurement
                ? collector.buildRecorder()
                : new PerKindLatencyRecorder(1);
        TailSnapshot tail = measurement
                ? collector.buildTailSnapshot()
                : new TailSnapshot(0, 0, 0, 0, 0, 0, Long.MIN_VALUE, List.of());

        return new PhaseResult(tasks, recorder, tail, submitted, submitStart, submitEnd,
                submitNanos, shutdownNanos, drainNanos, totalNanos,
                backpressure, backpressureWaitNanos, taskId);
    }

    private static Dispatcher createDispatcher(BenchmarkMode mode, int workerCount,
                                                HybridConfig hybrid, PinningConfig pinning,
                                                ShardedRoutingConfig routing) {
        return switch (mode) {
            case SHARED  -> new SharedOnlyDispatcher(workerCount, pinning);
            case SHARDED -> new ShardedOnlyDispatcher(workerCount, pinning, null,
                    routing == null ? ShardedRoutingConfig.defaults() : routing);
            case HYBRID  -> {
                if (hybrid == null) {
                    throw new IllegalArgumentException(
                            "mode=hybrid requires a hybrid config (top-level 'hybrid:' or per-run override). "
                                    + "There are no built-in routing defaults.");
                }
                yield new HybridDispatcher(hybrid);
            }
            default -> throw new IllegalArgumentException("Unsupported run mode for YAML execution: " + mode);
        };
    }

    private static void appendWorkloadSummary(StringBuilder sb, WorkloadConfig w) {
        sb.append("workloadEntries=").append(w.entries().size()).append('\n');
        int i = 0;
        for (WorkloadEntry e : w.entries()) {
            sb.append("entry[").append(i++).append("]=").append(e.summary()).append('\n');
        }
    }

    private static void appendCalibrationSummary(StringBuilder sb, TaskGenerator generator) {
        List<TaskGenerator.Calibration> cals = generator.calibrations();
        sb.append("calibrationEntries=").append(cals.size()).append('\n');
        int i = 0;
        for (TaskGenerator.Calibration c : cals) {
            sb.append("calibration[").append(i++).append("]=").append(c.summary()).append('\n');
        }
    }

    /* ================================================================
     *  Tail / drain diagnostic (read-only; pure stdout)
     * ================================================================
     *
     * Investigates why drain >> max-latency can happen, by:
     *   - counting tasks completed inside vs outside the submit window
     *   - flagging tasks with corrupted timing (startNanos=0 or
     *     finishNanos=0, which would inflate end-to-end / underflow
     *     execution into bogus values)
     *   - dumping the top-10 end-to-end latencies with their full
     *     timeline relative to submitStart, so we can see whether the
     *     observed "max" is a real workload event or a measurement bug
     *   - printing per-shard pending queue depth at submitEnd AND after
     *     drain (sharded only), plus per-worker measurement counts
     *
     * No fields on Task / Worker / Executor are mutated. No new timing
     * is introduced. This block runs after recorder.summary() has been
     * printed and is purely additive output.
     */
    /**
     * Appends the tail / drain diagnostic block to {@code out}. Single
     * formatting source so the section is byte-identical between
     * stdout and {@code summary_sharded50.txt}.
     *
     * <p>See class-level notes for what this block reveals; the body
     * is purely additive observation (no field mutation, no timing).
     */
    private static void appendTailDiagnostics(StringBuilder out,
                                              PhaseResult measurement,
                                              Dispatcher dispatcher,
                                              int[] shardQueueAtSubmitEnd) {
        TailSnapshot tail = measurement.tail();
        long submitStart   = measurement.submitStartNanos();
        long submitEndNs   = measurement.submitEndNanos();
        long shutdownEndNs      = submitEndNs + measurement.shutdownNanos();

        out.append(System.lineSeparator());
        out.append("=== Tail / Drain Diagnostic ===").append(System.lineSeparator());
        out.append(String.format("  measurementSubmitted               : %,d%n", measurement.submitted()));
        out.append(String.format("  completedDuringSubmitWindow        : %,d%n", tail.completedInSubmit()));
        out.append(String.format("  completedDuringShutdownWindow      : %,d%n", tail.completedInShutdown()));
        out.append(String.format("  completedDuringTaskDrainWindow     : %,d%n", tail.completedInDrain()));
        out.append(String.format("  tasksWithStartNanos==0  (skipped)  : %,d%n", tail.notStarted()));
        out.append(String.format("  tasksWithFinishNanos==0 (skipped)  : %,d%n", tail.notFinished()));
        out.append(String.format("  finishedAfterReportedDrainEnd      : %,d%n", tail.finishedAfterDrain()));
        long countedSum = tail.completedInSubmit() + tail.completedInShutdown() + tail.completedInDrain()
                + tail.notStarted() + tail.notFinished();
        if (countedSum != measurement.submitted()) {
            out.append(String.format("  *** COUNT MISMATCH: classified=%d but submitted=%d (delta=%d)%n",
                    countedSum, measurement.submitted(), countedSum - measurement.submitted()));
        }
        double submitDurMs   = (submitEndNs - submitStart) / 1_000_000.0;
        double shutdownDurMs = measurement.shutdownNanos() / 1_000_000.0;
        double drainDurMs    = measurement.drainNanos()    / 1_000_000.0;
        double lastFinishRelMs = tail.maxFinishNanos() == Long.MIN_VALUE
                ? Double.NaN : (tail.maxFinishNanos() - submitStart) / 1_000_000.0;
        out.append(String.format("  submitWindow                        : 0.000 ms .. %.3f ms%n", submitDurMs));
        out.append(String.format("  shutdownWindow  (session.stop)      : %.3f ms .. %.3f ms%n",
                submitDurMs, submitDurMs + shutdownDurMs));
        out.append(String.format("  taskDrainWindow (workload finish)   : %.3f ms .. %.3f ms%n",
                submitDurMs + shutdownDurMs, submitDurMs + shutdownDurMs + drainDurMs));
        out.append(String.format("  latestTaskFinishNanos (rel)         : %.3f ms%n", lastFinishRelMs));

        out.append(System.lineSeparator());
        out.append("  Top-10 tasks by end-to-end latency:").append(System.lineSeparator());
        out.append("    rank taskId      kind   meas phase     enq(ms)   start(ms)  finish(ms)   queueWait(ms)   exec(ms)   endToEnd(ms)")
           .append(System.lineSeparator());
        int rank = 1;
        for (TailTaskSample t : tail.topByEndToEnd()) {
            long enq = t.enqueuedNanos();
            long st  = t.startNanos();
            long fn  = t.finishNanos();
            String phase;
            if      (fn <= submitEndNs)   phase = "submit";
            else if (fn <= shutdownEndNs) phase = "shutdwn";
            else                          phase = "drain";
            out.append(String.format("    %4d %-10d  %-6s %-4s %-7s %9.3f  %10.3f  %10.3f   %12.3f  %9.3f   %12.3f%n",
                    rank++,
                    t.taskId(),
                    t.kind().name(),
                    t.measurement() ? "Y" : "N",
                    phase,
                    (enq == 0L ? 0.0 : (enq - submitStart) / 1_000_000.0),
                    (st - submitStart) / 1_000_000.0,
                    (fn - submitStart) / 1_000_000.0,
                    t.queueWaitNanos() / 1_000_000.0,
                    t.executionNanos() / 1_000_000.0,
                    t.endToEndNanos() / 1_000_000.0));
        }

        // Per-shard pending queue depth at submitEnd / after drain, and
        // per-worker measurement counts. Sharded only — no-op otherwise.
        if (dispatcher instanceof ShardedOnlyDispatcher d) {
            ShardedExecutor sx = d.executor();
            int n = sx.getWorkerCount();
            long[] measCounts = sx.getMeasurementProcessedCounts();
            long[] allCounts  = sx.getProcessedCounts();
            int sumAtSubmitEnd = 0;
            int sumAfterDrain  = 0;
            out.append(System.lineSeparator());
            out.append("  Per-shard pending queue size (at submitEnd / after drain) "
                    + "+ measurement-completed count per worker:").append(System.lineSeparator());
            for (int i = 0; i < n; i++) {
                int qEnd  = shardQueueAtSubmitEnd != null ? shardQueueAtSubmitEnd[i] : -1;
                int qPost = sx.getQueueSize(i);
                sumAtSubmitEnd += (qEnd < 0 ? 0 : qEnd);
                sumAfterDrain  += qPost;
                out.append(String.format("    shard[%2d]  submitEnd=%5s  postDrain=%5d   measCompleted=%,8d  allPhases=%,8d%n",
                        i,
                        qEnd < 0 ? "?" : String.valueOf(qEnd),
                        qPost,
                        measCounts[i],
                        allCounts[i]));
            }
            out.append(String.format("    TOTAL      submitEnd=%5d  postDrain=%5d%n",
                    sumAtSubmitEnd, sumAfterDrain));
            if (sumAfterDrain > 0) {
                out.append(String.format("    *** WARNING: %d task(s) still queued AFTER drain completed — "
                        + "drain accounting and recorded latencies may be inconsistent.%n", sumAfterDrain));
            }
        }
        out.append(System.lineSeparator());
    }

    private static Path parseConfigPath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                return Paths.get(arg.substring("--config=".length()).trim());
            }
        }
        throw new IllegalArgumentException("Missing required argument: --config=<path-to-yaml>");
    }
    /**
     * Reads a {@code <runName>.perf.stat.txt} file (produced by
     * {@link com.scott.profiling.PerfStatProfiler} via the existing
     * {@code perf stat -e ... -o file -p PID} mechanism) and appends a
     * small per-task normalized block to {@code summary}. Reuses the
     * existing perf-stat output as sole source; no new subprocess.
     *
     * <p>Format matches {@code perf stat} default: one event per line,
     * {@code  <count>  <event-name>  [# comment]}. Unsupported events
     * appear as {@code <not counted>} — treated as absent, matching
     * how the existing bridge handles unavailable counters.
     */
    private static void appendPerfStatPerTask(StringBuilder summary, Path perfStatPath, long submittedTasks) {
        if (perfStatPath == null || !Files.exists(perfStatPath)) return;
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "^\\s*([\\d,\\.]+)\\s+([A-Za-z0-9_.\\-:]+).*$");
            for (String line : Files.readAllLines(perfStatPath)) {
                java.util.regex.Matcher m = p.matcher(line);
                if (!m.matches()) continue;
                String raw = m.group(1).replace(",", "").replace(".", "");
                String name = m.group(2);
                try { counts.put(name, Long.parseLong(raw)); } catch (NumberFormatException ignore) { }
            }
        } catch (IOException e) {
            return;
        }
        if (counts.isEmpty()) return;

        long submitted = Math.max(1L, submittedTasks);
        long cycles = counts.getOrDefault("cycles", 0L);
        long instr  = counts.getOrDefault("instructions", 0L);
        long llcLM  = counts.getOrDefault("LLC-load-misses", 0L);
        long mig    = counts.getOrDefault("cpu-migrations", 0L);
        // Accept either the generic "numa_reads_addressed_to_*_dram" name
        // (rare, arch-specific) or the standard Intel PEBS event that is
        // present on Skylake / Cascade Lake / Ice Lake:
        //   mem_load_l3_miss_retired.local_dram   (a.k.a. .remote_dram)
        // Whichever is present in the perf.stat.txt file is used.
        long local  = firstNonZero(counts,
                "mem_load_l3_miss_retired.local_dram",
                "numa_reads_addressed_to_local_dram");
        long remote = firstNonZero(counts,
                "mem_load_l3_miss_retired.remote_dram",
                "numa_reads_addressed_to_remote_dram");
        double ipc         = cycles == 0 ? 0.0 : (double) instr / cycles;
        double remoteRatio = (local + remote) == 0L ? 0.0 : (double) remote / (local + remote);

        summary.append('\n').append("=== Perf Stat (aggregate, per-task normalized) ===").append('\n');
        summary.append(String.format("perfStat.cyclesPerTask=%.1f%n",           (double) cycles / submitted));
        summary.append(String.format("perfStat.instructionsPerTask=%.1f%n",     (double) instr  / submitted));
        summary.append(String.format("perfStat.ipc=%.3f%n",                     ipc));
        summary.append(String.format("perfStat.cpuMigrationsPerTask=%.4f%n",    (double) mig    / submitted));
        summary.append(String.format("perfStat.llcLoadMissesPerTask=%.2f%n",    (double) llcLM  / submitted));
        summary.append(String.format("perfStat.localDramReadsPerTask=%.2f%n",   (double) local  / submitted));
        summary.append(String.format("perfStat.remoteDramReadsPerTask=%.2f%n",  (double) remote / submitted));
        summary.append(String.format("perfStat.remoteDramRatio=%.4f%n",         remoteRatio));
        summary.append(String.format("perfStat.raw.cycles=%d, instructions=%d, llcLoadMisses=%d, "
                + "localDram=%d, remoteDram=%d, cpuMigrations=%d%n",
                cycles, instr, llcLM, local, remote, mig));
        summary.append(String.format("perfStat.file=%s%n", perfStatPath));
    }

    /** Returns the first {@code counts[key]} that is > 0, else 0. Lets the
     *  parser accept multiple spellings of the same hardware event across
     *  perf/kernel/arch variants (e.g. Cascade Lake vs Ice Lake naming). */
    private static long firstNonZero(java.util.Map<String, Long> counts, String... keys) {
        for (String k : keys) {
            Long v = counts.get(k);
            if (v != null && v > 0L) return v;
        }
        // Fall back to whatever the first key holds even if zero, so raw
        // counts still show up in the summary block.
        Long v = counts.get(keys[0]);
        return v == null ? 0L : v;
    }

    /**
     * Reads process CPU time (ns) via {@code com.sun.management.OperatingSystemMXBean}.
     * Returns -1 on any failure (non-HotSpot JVM, sandboxed env, etc.).
     * Called at most twice per run (measurement start / end) — zero
     * hot-path cost.
     */
    private static long readProcessCpuTimeNs() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long v = sun.getProcessCpuTime();
                return v < 0 ? -1L : v;
            }
        } catch (Throwable ignore) { /* fall through */ }
        return -1L;
    }
}
