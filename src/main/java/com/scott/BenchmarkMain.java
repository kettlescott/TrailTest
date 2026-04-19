package com.scott;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * YAML-driven benchmark runner.
 *
 * <p>Primary entrypoint: {@code --config=<path>}. The runner loads YAML,
 * validates it, executes warmup and measurement for each run, and prints/
 * writes results to {@code results/<runName>/summary.txt}.
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
                "${runName}.jfr", null, null)
                : root.profiling();

        JfrController jfr = JfrController.create(profiling, run.name(), runDir);

        try {
            System.out.println("========================================");
            System.out.printf("Run: %s%n", run.name());
            System.out.println("========================================");
            System.out.printf("  Mode             : %s%n", mode);
            System.out.printf("  Workload         : %s%n", run.workload());
            System.out.printf("  Workers          : %d%n", global.workerCount());
            System.out.printf("  Max in-flight    : %d%n", global.maxInflight());
            System.out.printf("  Warmup           : %d s%n", global.warmupSeconds());
            System.out.printf("  Measurement      : %d s%n", global.measurementSeconds());
            System.out.printf("  Task count       : %s%n",
                    global.taskCount() > 0 ? String.valueOf(global.taskCount()) : "unlimited (time-based)");
            System.out.println();

            long taskId = 0L;

            PhaseResult warmup = runPhase(dispatcher, generator, global.maxInflight(),
                    global.warmupSeconds() * 1_000_000_000L, false, 0, taskId);
            taskId = warmup.nextTaskId();

            System.out.printf("  Warmup done      : %,d tasks in %.3f s%n",
                    warmup.submitted(), warmup.elapsedNanos() / 1_000_000_000.0);

            jfr.startBeforeMeasurement();

            PhaseResult measurement = runPhase(dispatcher, generator, global.maxInflight(),
                    global.measurementSeconds() * 1_000_000_000L, true, global.taskCount(), taskId);

            jfr.stopAfterMeasurement();

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
            if (jfr.outputFile() != null) {
                System.out.printf("  JFR file         : %s%n", jfr.outputFile());
            }
        } finally {
            dispatcher.shutdown();
            dispatcher.awaitTermination(30, TimeUnit.SECONDS);
            jfr.close();
        }
    }

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

    private static Path parseConfigPath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                return Paths.get(arg.substring("--config=".length()).trim());
            }
        }
        throw new IllegalArgumentException("Missing required argument: --config=<path-to-yaml>");
    }
}
