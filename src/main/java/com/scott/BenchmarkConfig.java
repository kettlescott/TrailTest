package com.scott;

/**
 * Immutable benchmark configuration that captures every parameter needed
 * to reproduce an identical workload across separate JVM invocations.
 *
 * <h3>Why this exists</h3>
 * <p>When {@code --mode=shared} and {@code --mode=sharded} run in separate
 * processes (for clean JFR recordings), each JVM independently calibrates
 * {@code iterations} and recomputes config.  Differences in JIT state,
 * CPU frequency scaling, or background load cause the two runs to use
 * <em>different</em> workloads — breaking the apples-to-apples guarantee.
 *
 * <p>{@code BenchmarkConfig} solves this by externalising every tuneable
 * into a single object that can be:
 * <ul>
 *   <li><strong>Calibrated dynamically</strong> (legacy behaviour), or</li>
 *   <li><strong>Fixed from CLI arguments</strong> produced by
 *       {@code --mode=prepare}.</li>
 * </ul>
 *
 * <h3>Open-loop model</h3>
 * <p>The benchmark uses an open-loop / steady-arrival submission model.
 * Instead of pre-generating a fixed task count and submitting in lock-step
 * batches, tasks are submitted continuously for a fixed duration with
 * {@link java.util.concurrent.Semaphore}-gated backpressure.  The actual
 * task count is determined at runtime by how many tasks the executor can
 * process within the given duration.
 *
 * <h3>Workflow</h3>
 * <pre>
 *   # 1. Calibrate once:
 *   java ... BenchmarkMain --mode=prepare
 *
 *   # 2. Copy the printed values into shared &amp; sharded runs:
 *   java ... BenchmarkMain --mode=shared  --iterations=180472 --warmupSeconds=10 ...
 *   java ... BenchmarkMain --mode=sharded --iterations=180472 --warmupSeconds=10 ...
 * </pre>
 *
 * @param workerCount        number of executor worker threads
 * @param maxInflight        maximum tasks in flight (Semaphore permits)
 * @param seed               base seed for deterministic workload generation
 * @param iterations         iteration count passed to every {@link CpuBoundWorkload}
 * @param warmupSeconds      warmup phase duration in seconds
 * @param measurementSeconds measurement phase duration in seconds
 * @param targetTaskNanos    desired per-task execution time (metadata — not used at runtime)
 * @param taskCount          fixed number of tasks to submit per measurement phase
 *                           (0 = unlimited — run until time deadline, existing behaviour)
 */
public record BenchmarkConfig(
        int  workerCount,
        int  maxInflight,
        long seed,
        int  iterations,
        int  warmupSeconds,
        int  measurementSeconds,
        long targetTaskNanos,
        int  taskCount
) {

    /* ---- compact constructor with validation ---- */

    public BenchmarkConfig {
        if (workerCount <= 0)
            throw new IllegalArgumentException("workerCount must be > 0, got " + workerCount);
        if (maxInflight <= 0)
            throw new IllegalArgumentException("maxInflight must be > 0, got " + maxInflight);
        if (iterations <= 0)
            throw new IllegalArgumentException("iterations must be > 0, got " + iterations);
        if (warmupSeconds < 0)
            throw new IllegalArgumentException("warmupSeconds must be >= 0, got " + warmupSeconds);
        if (measurementSeconds <= 0)
            throw new IllegalArgumentException("measurementSeconds must be > 0, got " + measurementSeconds);
        if (taskCount < 0)
            throw new IllegalArgumentException("taskCount must be >= 0 (0 = unlimited), got " + taskCount);
    }

    /* ================================================================
     *  Machine-readable output (for --mode=prepare)
     * ================================================================ */

    /**
     * Returns a machine-readable key=value block suitable for copy-paste
     * into scripts or documentation.
     */
    public String toFixedConfigBlock() {
        String base = String.format("""
                iterations=%d
                warmupSeconds=%d
                measurementSeconds=%d
                workerCount=%d
                maxInflight=%d
                seed=%d""",
                iterations, warmupSeconds, measurementSeconds,
                workerCount, maxInflight, seed);
        return taskCount > 0 ? base + "\ntaskCount=" + taskCount : base;
    }

    /**
     * Returns the CLI arguments needed to replay this exact config.
     */
    public String toCliArgs() {
        String base = String.format(
                "--iterations=%d --warmupSeconds=%d --measurementSeconds=%d --seed=%d --workerCount=%d --maxInflight=%d",
                iterations, warmupSeconds, measurementSeconds, seed, workerCount, maxInflight);
        return taskCount > 0 ? base + " --taskCount=" + taskCount : base;
    }

    /* ================================================================
     *  CLI argument parsing
     * ================================================================ */

    /**
     * Attempts to build a {@code BenchmarkConfig} from explicit CLI
     * arguments.  Returns {@code null} if no fixed-config arguments are
     * present (meaning the caller should fall back to dynamic calibration).
     *
     * <p>If <em>any</em> of {@code --iterations}, {@code --warmupSeconds},
     * or {@code --measurementSeconds} is provided, <em>all three</em> are
     * required; otherwise the method prints an error and exits.
     *
     * <p>{@code --seed}, {@code --workerCount}, and {@code --maxInflight}
     * are optional and fall back to sensible defaults.
     */
    public static BenchmarkConfig fromArgs(String[] args, long defaultSeed, long defaultTargetNanos) {
        Integer iterations         = parseIntArg(args, "--iterations");
        Integer warmupSeconds      = parseIntArg(args, "--warmupSeconds");
        Integer measurementSeconds = parseIntArg(args, "--measurementSeconds");

        boolean hasAny = iterations != null || warmupSeconds != null || measurementSeconds != null;
        if (!hasAny) return null;   // no fixed-config flags → caller should calibrate

        // If any are provided, all three are required
        if (iterations == null || warmupSeconds == null || measurementSeconds == null) {
            System.err.println("ERROR: When using fixed config, all three are required:");
            System.err.println("  --iterations=<n>  --warmupSeconds=<n>  --measurementSeconds=<n>");
            System.err.printf("  Provided: iterations=%s  warmupSeconds=%s  measurementSeconds=%s%n",
                    iterations, warmupSeconds, measurementSeconds);
            System.exit(1);
        }

        Long   seedArg  = parseLongArg(args, "--seed");
        long   seed     = seedArg != null ? seedArg : defaultSeed;

        Integer wcArg   = parseIntArg(args, "--workerCount");
        int workerCount = wcArg != null ? wcArg : Runtime.getRuntime().availableProcessors();

        Integer miArg    = parseIntArg(args, "--maxInflight");
        int maxInflight  = miArg != null ? miArg : workerCount * 2;

        Integer tcArg   = parseIntArg(args, "--taskCount");
        int taskCount   = tcArg != null ? tcArg : 0;

        return new BenchmarkConfig(workerCount, maxInflight, seed,
                iterations, warmupSeconds, measurementSeconds, defaultTargetNanos, taskCount);
    }

    /* ---- primitive arg parsers ---- */

    static Integer parseIntArg(String[] args, String name) {
        String prefix = name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                try {
                    return Integer.parseInt(arg.substring(prefix.length()).trim());
                } catch (NumberFormatException e) {
                    System.err.printf("ERROR: invalid integer for %s: '%s'%n", name, arg);
                    System.exit(1);
                }
            }
        }
        return null;
    }

    static Long parseLongArg(String[] args, String name) {
        String prefix = name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                try {
                    return Long.parseLong(arg.substring(prefix.length()).trim());
                } catch (NumberFormatException e) {
                    System.err.printf("ERROR: invalid long for %s: '%s'%n", name, arg);
                    System.exit(1);
                }
            }
        }
        return null;
    }
}
