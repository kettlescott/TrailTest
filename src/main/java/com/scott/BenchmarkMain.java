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
                               long elapsedNanos,
                               int backpressureEvents,
                               long nextTaskId) {}

    public static void main(String[] args) throws Exception {
        Path configPath = parseConfigPath(args);
        RootConfig root = BenchmarkConfigLoader.load(configPath);
        root.validate();

        int baseIterations = root.global().baseIterations() != null
                ? root.global().baseIterations()
                : WorkloadCalibrator.calibrateIterations(root.global().targetTaskNanos(), root.global().seed());

        System.out.println("=== YAML Benchmark Plan ===");
        System.out.printf("  Config file      : %s%n", configPath);
        System.out.printf("  Base iterations  : %,d%n", baseIterations);
        System.out.printf("  Runs             : %d%n", root.runs().size());
        System.out.println();

        for (RunConfig run : root.runs()) {
            executeRun(root, run, baseIterations);
            System.out.println();
        }
    }

    private static void executeRun(RootConfig root, RunConfig run, int baseIterations) throws Exception {
        GlobalConfig global = root.global();
        WorkloadConfig workload = root.workloads().get(run.workload());
        BenchmarkMode mode = BenchmarkMode.fromConfigValue(run.mode());

        if (mode != BenchmarkMode.SHARED && mode != BenchmarkMode.SHARDED) {
            throw new IllegalArgumentException("runs[].mode must be shared|sharded for YAML runs: " + run.mode());
        }

        TaskGenerator generator = new TaskGenerator(workload, baseIterations, global.seed());
        Dispatcher dispatcher = createDispatcher(mode, global.workerCount());

        Path runDir = Paths.get("results", run.name());
        Files.createDirectories(runDir);

        ProfilingConfig profiling = root.profiling() == null
                ? new ProfilingConfig(false, "cli", "profile", "beforeMeasurement", "afterMeasurement",
                        "${runName}.jfr", null, null, null)
                : root.profiling();

        ProfilingSession session = buildSession(profiling, run.name(), runDir);
        Path jfrOutput  = jfrOutputPath(profiling, run.name(), runDir);
        Path perfOutput = perfOutputPath(profiling, run.name(), runDir);
        Path asyncOutput = asyncProfilerOutputPath(profiling, run.name(), runDir);

        MeasurementContext ctx = new MeasurementContext(
                run.name(), mode.name(), run.workload(), global.workerCount(), "");

        try {
            System.out.println("========================================");
            System.out.printf("Run: %s%n", run.name());
            System.out.println("========================================");
            System.out.printf("  Mode             : %s%n", mode);
            System.out.printf("  Workload         : %s%n", run.workload());
            System.out.printf("  Resource         : %s%n", workload.resourceType().label());
            if (workload.isSingle()) {
                System.out.printf("  Profile          : %s%n",
                        workload.profile().summary(workload.resourceType()));
            } else {
                System.out.println("  Components       :");
                for (WorkloadComponentConfig c : workload.components()) {
                    System.out.printf("    - %-16s weight=%3d  resource=%-6s  %s%n",
                            c.name(), c.weight(), c.resource(),
                            c.profile().summary(c.resourceType()));
                }
            }
            System.out.printf("  Workers          : %d%n", global.workerCount());
            System.out.printf("  Max in-flight    : %d%n", global.maxInflight());
            System.out.printf("  Warmup           : %d s%n", global.warmupSeconds());
            System.out.printf("  Measurement      : %d s%n", global.measurementSeconds());
            System.out.printf("  Task count       : %s%n",
                    global.taskCount() > 0 ? String.valueOf(global.taskCount()) : "unlimited (time-based)");
            System.out.println();

            long taskId = 0L;

            // 1. Warmup — profilers are NOT running.
            PhaseResult warmup = runPhase(dispatcher, generator, global.maxInflight(),
                    global.warmupSeconds() * 1_000_000_000L, false, 0, taskId);
            taskId = warmup.nextTaskId();

            System.out.printf("  Warmup done      : %,d tasks in %.3f s%n",
                    warmup.submitted(), warmup.elapsedNanos() / 1_000_000_000.0);

            // 2. Start profilers and let startup noise settle.
            session.start();
            session.beforeMeasurement();

            // 3. Measurement window, bracketed by JFR anchor events.
            session.markMeasurementStart(ctx);
            PhaseResult measurement = runPhase(dispatcher, generator, global.maxInflight(),
                    global.measurementSeconds() * 1_000_000_000L, true, global.taskCount(), taskId);
            session.markMeasurementStop(ctx);

            // 4. Stop profilers BEFORE dispatcher shutdown so the 10–30 s
            //    drain cost is never part of the recording.
            session.stop();

            double measureSecs = measurement.elapsedNanos() / 1_000_000_000.0;
            double throughput = measurement.submitted() / Math.max(measureSecs, 1e-9);

            LatencyRecorder recorder = new LatencyRecorder(measurement.submitted());
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
            appendWorkloadSummary(summary, workload);
            summary.append("submitted=").append(measurement.submitted()).append('\n');
            summary.append("durationSeconds=").append(String.format("%.3f", measureSecs)).append('\n');
            summary.append("throughput=").append(String.format("%.1f", throughput)).append(" tasks/s\n");
            summary.append("backpressureEvents=").append(measurement.backpressureEvents()).append("\n\n");
            summary.append(recorder.summary());

            Path summaryPath = runDir.resolve("summary.txt");
            Files.writeString(summaryPath, summary.toString());

            System.out.printf("  Measurement done : %,d tasks in %.3f s%n", measurement.submitted(), measureSecs);
            System.out.printf("  Throughput       : %,.1f tasks/s%n", throughput);
            System.out.printf("  Backpressure     : %,d%n", measurement.backpressureEvents());
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
            writeRunMetadata(runDir, run, global, mode, profiling, throughput,
                    measurement.submitted(), measureSecs, jfrOutput, perfOutput, asyncOutput);
        } finally {
            // Defensive: if anything above threw, still stop profilers first
            // (idempotent) so cleanup time isn't recorded, then shut down
            // the dispatcher.
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
                profiling.startCommand(), profiling.stopCommand()));

        PerfConfig perf = profiling.perf();
        if (perf != null && perf.enabled()) {
            // Insert perf FIRST in start order so its window brackets JFR.
            // CompositeProfiler stops in reverse order, so perf stops LAST —
            // which is what we want for clean timeline alignment.
            children.add(0, new PerfProfiler(perf, runName, runDir));
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
                                         double throughput,
                                         int submitted,
                                         double measureSecs,
                                         Path jfrOutput,
                                         Path perfOutput,
                                         Path asyncOutput) throws java.io.IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mode",                 mode.name());
        fields.put("workload",             run.workload());
        fields.put("workerCount",          global.workerCount());
        fields.put("maxInflight",          global.maxInflight());
        fields.put("warmupSeconds",        global.warmupSeconds());
        fields.put("measurementSeconds",   global.measurementSeconds());
        fields.put("taskCount",            global.taskCount());
        fields.put("submitted",            submitted);
        fields.put("durationSeconds",      String.format("%.3f", measureSecs));
        fields.put("throughputPerSecond",  String.format("%.1f", throughput));
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
     *  Phase execution (unchanged)
     * ================================================================ */

    private static PhaseResult runPhase(Dispatcher dispatcher,
                                        TaskGenerator generator,
                                        int maxInflight,
                                        long durationNanos,
                                        boolean measurement,
                                        int taskLimit,
                                        long startTaskId) throws InterruptedException {
        Semaphore permits = new Semaphore(maxInflight);
        Runnable releasePermit = permits::release;

        long phaseStart = System.nanoTime();
        long deadline = phaseStart + durationNanos;

        int submitted = 0;
        int backpressure = 0;
        long taskId = startTaskId;

        int initialCapacity = taskLimit > 0 ? taskLimit : 4096;
        List<Task> tasks = new ArrayList<>(initialCapacity);

        while ((taskLimit > 0 ? submitted < taskLimit : System.nanoTime() < deadline)) {
            if (!permits.tryAcquire()) {
                backpressure++;
                permits.acquire();
            }

            Task task = generator.nextTask(taskId, measurement, releasePermit);
            dispatcher.submit(task);
            tasks.add(task);

            taskId++;
            submitted++;
        }

        permits.acquire(maxInflight);
        permits.release(maxInflight);

        long elapsed = System.nanoTime() - phaseStart;
        return new PhaseResult(tasks, submitted, elapsed, backpressure, taskId);
    }

    private static Dispatcher createDispatcher(BenchmarkMode mode, int workerCount) {
        return switch (mode) {
            case SHARED -> new SharedOnlyDispatcher(workerCount);
            case SHARDED -> new ShardedOnlyDispatcher(workerCount);
            default -> throw new IllegalArgumentException("Unsupported run mode for YAML execution: " + mode);
        };
    }

    private static void appendWorkloadSummary(StringBuilder sb, WorkloadConfig w) {
        sb.append("workloadResource=").append(w.resourceType().label()).append('\n');
        sb.append("workloadMode=").append(w.mode()).append('\n');
        if (w.isSingle()) {
            sb.append("workloadProfile=").append(w.profile().summary(w.resourceType())).append('\n');
        } else {
            sb.append("workloadGeneration=").append(w.generation()).append('\n');
            int i = 0;
            for (WorkloadComponentConfig c : w.components()) {
                sb.append("component[").append(i++).append("]=")
                        .append("name=").append(c.name())
                        .append(", weight=").append(c.weight())
                        .append(", resource=").append(c.resource())
                        .append(", ").append(c.profile().summary(c.resourceType()))
                        .append('\n');
            }
        }
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
