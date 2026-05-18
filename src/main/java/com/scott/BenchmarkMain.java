package com.scott;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private record PhaseResult(List<Task> tasks,
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

        TaskGenerator generator = new TaskGenerator(workload, global.seed());

        // Resolve effective hybrid config (per-run override > root). Required
        // for HYBRID mode; ignored for SHARED/SHARDED.
        HybridConfig effectiveHybrid = run.hybrid() != null ? run.hybrid() : root.hybrid();
        PinningConfig pinning = run.pinning() == null ? PinningConfig.disabled() : run.pinning();
        pinning.validate(run.name(), mode, global.workerCount());
        Dispatcher dispatcher = createDispatcher(mode, global.workerCount(), effectiveHybrid, pinning);

        Path runDir = Paths.get("results", run.name());
        Files.createDirectories(runDir);

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
                    global.warmupSeconds() * 1_000_000_000L, false, 0, taskId, null);
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
            PhaseResult measurement = runPhase(dispatcher, generator, global.maxInflight(),
                    global.measurementSeconds() * 1_000_000_000L, true, global.taskCount(), taskId,
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
                    });

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

            PerKindLatencyRecorder recorder = new PerKindLatencyRecorder(measurement.submitted());
            for (Task task : measurement.tasks()) {
                recorder.record(task);
            }

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

            Path summaryPath = runDir.resolve("summary.txt");
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
                    jfrOutput, perfOutput, asyncOutput);

            // 6. Diagnostic: per-shard queue distribution (sharded mode only).
            //    Pure stdout output — does not affect timing, latency, or
            //    queue behavior. Runs after all metrics are finalized and
            //    before dispatcher shutdown in the finally block.
            if (dispatcher instanceof ShardedOnlyDispatcher d) {
                d.executor().printQueueDistribution();
            }

            // 7. DIAGNOSTIC: tail-latency / drain-accounting sanity check.
            //    Pure observation — touches no executor, no queue, no
            //    timing field. Helps identify whether a huge max latency
            //    is a real workload tail or a measurement artifact
            //    (e.g. uninitialized startNanos on a never-executed task,
            //    or accounting drift between drain and recorded latency).
            printTailDiagnostics(measurement, dispatcher, shardQueueAtSubmitEnd);
        } finally {
            try {
                session.stop();
            } catch (Exception ignore) {
                /* best-effort on failure path */
            } finally {
                dispatcher.shutdown();
                dispatcher.awaitTermination(30, TimeUnit.SECONDS);
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

    /* ================================================================
     *  Phase execution with bounded in-flight submission
     * ================================================================ */

    private static PhaseResult runPhase(Dispatcher dispatcher,
                                        TaskGenerator generator,
                                        int maxInflight,
                                        long durationNanos,
                                        boolean measurement,
                                        int taskLimit,
                                        long startTaskId,
                                        Runnable onSubmitEnd) throws InterruptedException {
        Semaphore permits = new Semaphore(maxInflight);
        Runnable releasePermit = permits::release;

        long submitStart = System.nanoTime();
        long deadline = submitStart + durationNanos;

        int submitted = 0;
        int backpressure = 0;
        long backpressureWaitNanos = 0L;
        long taskId = startTaskId;

        // Only retain Task references for the measurement phase. Warmup
        // tasks are dispatched and discarded — keeping them would bloat
        // the heap and do nothing useful (they are excluded from latency
        // analysis by the LatencyRecorder anyway).
        int initialCapacity = !measurement ? 0 : (taskLimit > 0 ? taskLimit : 4096);
        List<Task> tasks = new ArrayList<>(initialCapacity);

        while ((taskLimit > 0 ? submitted < taskLimit : System.nanoTime() < deadline)) {
            if (!permits.tryAcquire()) {
                backpressure++;
                long waitStart = System.nanoTime();
                permits.acquire();
                backpressureWaitNanos += System.nanoTime() - waitStart;
            }

            Task task = generator.nextTask(taskId, measurement, releasePermit);
            dispatcher.submit(task);
            if (measurement) {
                tasks.add(task);
            }

            taskId++;
            submitted++;
        }

        long submitEnd = System.nanoTime();

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

        return new PhaseResult(tasks, submitted, submitStart, submitEnd,
                submitNanos, shutdownNanos, drainNanos, totalNanos,
                backpressure, backpressureWaitNanos, taskId);
    }

    private static Dispatcher createDispatcher(BenchmarkMode mode, int workerCount,
                                                HybridConfig hybrid, PinningConfig pinning) {
        return switch (mode) {
            case SHARED  -> new SharedOnlyDispatcher(workerCount, pinning);
            case SHARDED -> new ShardedOnlyDispatcher(workerCount, pinning);
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
    private static void printTailDiagnostics(PhaseResult measurement,
                                             Dispatcher dispatcher,
                                             int[] shardQueueAtSubmitEnd) {
        List<Task> tasks = measurement.tasks();
        long submitStart   = measurement.submitStartNanos();
        long submitEndNs   = measurement.submitEndNanos();

        int completedInSubmit   = 0;
        int completedInShutdown = 0;
        int completedInDrain    = 0;
        int notStarted          = 0; // startNanos == 0
        int notFinished         = 0; // startNanos > 0 but finishNanos == 0
        int finishedAfterDrain  = 0; // finishNanos > submitEnd + shutdown + drain (shouldn't happen)
        long maxFinishNs        = Long.MIN_VALUE;
        long shutdownEndNs      = submitEndNs + measurement.shutdownNanos();
        long submitPlusDrainNs  = submitEndNs + measurement.shutdownNanos() + measurement.drainNanos();

        for (Task t : tasks) {
            long s = t.startNanos();
            long f = t.finishNanos();
            if (s == 0L) {
                notStarted++;
                continue;
            }
            if (f == 0L) {
                notFinished++;
                continue;
            }
            if      (f <= submitEndNs)   completedInSubmit++;
            else if (f <= shutdownEndNs) completedInShutdown++;
            else                         completedInDrain++;
            if (f > submitPlusDrainNs) finishedAfterDrain++;
            if (f > maxFinishNs) maxFinishNs = f;
        }

        // Top-10 by end-to-end latency (ignore tasks with corrupted timing).
        java.util.PriorityQueue<Task> top10 =
                new java.util.PriorityQueue<>(11, java.util.Comparator.comparingLong(Task::endToEndLatencyNanos));
        for (Task t : tasks) {
            if (t.startNanos() == 0L || t.finishNanos() == 0L) continue;
            if (top10.size() < 10) {
                top10.add(t);
            } else if (t.endToEndLatencyNanos() > top10.peek().endToEndLatencyNanos()) {
                top10.poll();
                top10.add(t);
            }
        }
        List<Task> sortedTop = new ArrayList<>(top10);
        sortedTop.sort((a, b) -> Long.compare(b.endToEndLatencyNanos(), a.endToEndLatencyNanos()));

        System.out.println();
        System.out.println("=== Tail / Drain Diagnostic ===");
        System.out.printf("  measurementSubmitted               : %,d%n", measurement.submitted());
        System.out.printf("  completedDuringSubmitWindow        : %,d%n", completedInSubmit);
        System.out.printf("  completedDuringShutdownWindow      : %,d%n", completedInShutdown);
        System.out.printf("  completedDuringTaskDrainWindow     : %,d%n", completedInDrain);
        System.out.printf("  tasksWithStartNanos==0  (skipped)  : %,d%n", notStarted);
        System.out.printf("  tasksWithFinishNanos==0 (skipped)  : %,d%n", notFinished);
        System.out.printf("  finishedAfterReportedDrainEnd      : %,d%n", finishedAfterDrain);
        long countedSum = completedInSubmit + completedInShutdown + completedInDrain + notStarted + notFinished;
        if (countedSum != measurement.submitted()) {
            System.out.printf("  *** COUNT MISMATCH: classified=%d but submitted=%d (delta=%d)%n",
                    countedSum, measurement.submitted(), countedSum - measurement.submitted());
        }
        double submitDurMs   = (submitEndNs - submitStart) / 1_000_000.0;
        double shutdownDurMs = measurement.shutdownNanos() / 1_000_000.0;
        double drainDurMs    = measurement.drainNanos()    / 1_000_000.0;
        double lastFinishRelMs = maxFinishNs == Long.MIN_VALUE
                ? Double.NaN : (maxFinishNs - submitStart) / 1_000_000.0;
        System.out.printf("  submitWindow                        : 0.000 ms .. %.3f ms%n", submitDurMs);
        System.out.printf("  shutdownWindow  (session.stop)      : %.3f ms .. %.3f ms%n",
                submitDurMs, submitDurMs + shutdownDurMs);
        System.out.printf("  taskDrainWindow (workload finish)   : %.3f ms .. %.3f ms%n",
                submitDurMs + shutdownDurMs, submitDurMs + shutdownDurMs + drainDurMs);
        System.out.printf("  latestTaskFinishNanos (rel)         : %.3f ms%n", lastFinishRelMs);

        System.out.println();
        System.out.println("  Top-10 tasks by end-to-end latency:");
        System.out.println("    rank taskId      kind   meas phase     enq(ms)   start(ms)  finish(ms)   queueWait(ms)   exec(ms)   endToEnd(ms)");
        int rank = 1;
        for (Task t : sortedTop) {
            long enq = t.enqueuedNanos();
            long st  = t.startNanos();
            long fn  = t.finishNanos();
            String phase;
            if      (fn <= submitEndNs)   phase = "submit";
            else if (fn <= shutdownEndNs) phase = "shutdwn";
            else                          phase = "drain";
            System.out.printf("    %4d %-10d  %-6s %-4s %-7s %9.3f  %10.3f  %10.3f   %12.3f  %9.3f   %12.3f%n",
                    rank++,
                    t.taskId(),
                    t.workloadKind().name(),
                    t.isMeasurement() ? "Y" : "N",
                    phase,
                    (enq == 0L ? 0.0 : (enq - submitStart) / 1_000_000.0),
                    (st - submitStart) / 1_000_000.0,
                    (fn - submitStart) / 1_000_000.0,
                    t.queueWaitTimeNanos() / 1_000_000.0,
                    t.executionTimeNanos() / 1_000_000.0,
                    t.endToEndLatencyNanos() / 1_000_000.0);
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
            System.out.println();
            System.out.println("  Per-shard pending queue size (at submitEnd / after drain) "
                    + "+ measurement-completed count per worker:");
            for (int i = 0; i < n; i++) {
                int qEnd  = shardQueueAtSubmitEnd != null ? shardQueueAtSubmitEnd[i] : -1;
                int qPost = sx.getQueueSize(i);
                sumAtSubmitEnd += (qEnd < 0 ? 0 : qEnd);
                sumAfterDrain  += qPost;
                System.out.printf("    shard[%2d]  submitEnd=%5s  postDrain=%5d   measCompleted=%,8d  allPhases=%,8d%n",
                        i,
                        qEnd < 0 ? "?" : String.valueOf(qEnd),
                        qPost,
                        measCounts[i],
                        allCounts[i]);
            }
            System.out.printf("    TOTAL      submitEnd=%5d  postDrain=%5d%n",
                    sumAtSubmitEnd, sumAfterDrain);
            if (sumAfterDrain > 0) {
                System.out.printf("    *** WARNING: %d task(s) still queued AFTER drain completed — "
                        + "drain accounting and recorded latencies may be inconsistent.%n", sumAfterDrain);
            }
        }
        System.out.println();
    }

    private static Path parseConfigPath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                return Paths.get(arg.substring("--config=".length()).trim());
            }
        }
        throw new IllegalArgumentException("Missing required argument: --config=<path-to-yaml>");
    }
}
