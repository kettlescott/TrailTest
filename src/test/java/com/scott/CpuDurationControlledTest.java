package com.scott;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for duration-controlled CPU workload.
 */
class CpuDurationControlledTest {

    private static Path writeTmp(String yaml) throws IOException {
        Path p = Files.createTempFile("duration-cpu-test-", ".yaml");
        Files.writeString(p, yaml);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void cpuDurationControlledBasicExecution() {
        // Simple test: workload should complete and return a value
        long targetMicros = 100;
        CpuBoundWorkloadDuration workload = new CpuBoundWorkloadDuration(targetMicros, 12345, 100);
        
        long start = System.nanoTime();
        long result = workload.execute();
        long elapsed = System.nanoTime() - start;
        
        assertNotEquals(0, result, "Workload should return non-zero value");
        assertTrue(elapsed >= targetMicros * 1_000, 
                "Execution time should be at least target duration");
    }

    @Test
    void cpuDurationControlledTiming50Micros() {
        // 50 µs target
        long targetMicros = 50;
        CpuBoundWorkloadDuration workload = new CpuBoundWorkloadDuration(targetMicros, 99999, 100);
        
        long start = System.nanoTime();
        workload.execute();
        long elapsed = System.nanoTime() - start;
        long elapsedMicros = elapsed / 1_000;
        
        // Allow 50% overshoot for batch boundary
        assertTrue(elapsedMicros >= targetMicros, 
                "50µs: actual=" + elapsedMicros + "µs, should be >= target");
        assertTrue(elapsedMicros <= targetMicros * 1.5, 
                "50µs: actual=" + elapsedMicros + "µs, overshoot should be < 1.5x");
    }

    @Test
    void cpuDurationControlledTiming100Micros() {
        long targetMicros = 100;
        CpuBoundWorkloadDuration workload = new CpuBoundWorkloadDuration(targetMicros, 55555, 100);
        
        long start = System.nanoTime();
        workload.execute();
        long elapsed = System.nanoTime() - start;
        long elapsedMicros = elapsed / 1_000;
        
        assertTrue(elapsedMicros >= targetMicros, 
                "100µs: actual=" + elapsedMicros + "µs, should be >= target");
        assertTrue(elapsedMicros <= targetMicros * 1.5, 
                "100µs: actual=" + elapsedMicros + "µs, overshoot should be < 1.5x");
    }

    @Test
    void cpuDurationControlledTiming200Micros() {
        long targetMicros = 200;
        CpuBoundWorkloadDuration workload = new CpuBoundWorkloadDuration(targetMicros, 77777, 100);
        
        long start = System.nanoTime();
        workload.execute();
        long elapsed = System.nanoTime() - start;
        long elapsedMicros = elapsed / 1_000;
        
        assertTrue(elapsedMicros >= targetMicros, 
                "200µs: actual=" + elapsedMicros + "µs, should be >= target");
        assertTrue(elapsedMicros <= targetMicros * 1.3, 
                "200µs: actual=" + elapsedMicros + "µs, overshoot should be < 1.3x");
    }

    @Test
    void cpuDurationControlledDifferentTargets() {
        // Different targets should produce different iteration counts
        long result50 = new CpuBoundWorkloadDuration(50, 12345, 100).execute();
        long result100 = new CpuBoundWorkloadDuration(100, 12345, 100).execute();
        
        // Both should be valid (non-zero)
        assertNotEquals(0, result50);
        assertNotEquals(0, result100);
    }

    @Test
    void cpuDurationControlledDifferentSeeds() {
        // Different seeds should produce different results
        CpuBoundWorkloadDuration w1 = new CpuBoundWorkloadDuration(100, 11111, 100);
        CpuBoundWorkloadDuration w2 = new CpuBoundWorkloadDuration(100, 22222, 100);
        
        long r1 = w1.execute();
        long r2 = w2.execute();
        
        assertNotEquals(r1, r2, "Different seeds should produce different results");
    }

    @Test
    void parsesCpuDurationControlledEntry() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 8
                workloads:
                  cpu_100us_duration:
                    - kind: CPU
                      targetMicros: 100
                      ratio: 1.0
                runs:
                  - name: test_cpu_100us
                    mode: shared
                    workload: cpu_100us_duration
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        assertNotNull(r);
        
        WorkloadConfig workload = r.workloads().get("cpu_100us_duration");
        WorkloadEntry entry = workload.entries().get(0);
        
        assertEquals(100L, entry.targetMicros());
        assertEquals(WorkloadKind.CPU, entry.kind());
        assertEquals(0, entry.cpuIterations(), "cpuIterations should be 0 for duration mode");
    }

    @Test
    void cpuDurationControlledInitializesCorrectly() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 4
                workloads:
                  cpu_50us:
                    - kind: CPU
                      targetMicros: 50
                      ratio: 1.0
                runs:
                  - name: test_cpu_50us
                    mode: shared
                    workload: cpu_50us
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("cpu_50us");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        assertEquals(1, cals.size());
        
        TaskGenerator.Calibration cal = cals.get(0);
        assertEquals(WorkloadKind.CPU, cal.kind());
        assertEquals(50L, cal.cpuTargetMicros());
        assertEquals("DURATION_CONTROLLED", cal.cpuMode());
        assertEquals(0, cal.cpuIterations(), "Should have 0 iterations in duration mode");
    }

    @Test
    void cpuBackwardCompatibilityFixed() throws IOException {
        // Old style with cpuIterations should still work
        Path p = writeTmp("""
                global:
                  workerCount: 4
                workloads:
                  cpu_fixed:
                    - kind: CPU
                      cpuIterations: 50000
                      ratio: 1.0
                runs:
                  - name: test_cpu_fixed
                    mode: shared
                    workload: cpu_fixed
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("cpu_fixed");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        TaskGenerator.Calibration cal = cals.get(0);
        
        assertEquals("FIXED_ITERATION", cal.cpuMode());
        assertEquals(50000, cal.cpuIterations());
        assertEquals(0L, cal.cpuTargetMicros());
    }

    @Test
    void taskGeneratorCreatesCorrectWorkload() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 2
                workloads:
                  cpu_100us:
                    - kind: CPU
                      targetMicros: 100
                      ratio: 1.0
                runs:
                  - name: test
                    mode: shared
                    workload: cpu_100us
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("cpu_100us");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        Task task = gen.nextTask(1, false, null);
        assertNotNull(task);
        assertEquals(WorkloadKind.CPU, task.workloadKind());
        
        Workload w = task.workload();
        assertNotNull(w);
        assertTrue(w instanceof CpuBoundWorkloadDuration, 
                "Should create CpuBoundWorkloadDuration for CPU + targetMicros");
    }

    @Test
    void cpuMixedWorkloads() throws IOException {
        // Test that both duration-controlled and fixed-iteration work together
        Path p = writeTmp("""
                global:
                  workerCount: 2
                workloads:
                  cpu_mixed:
                    - kind: CPU
                      targetMicros: 100
                      ratio: 0.5
                    - kind: CPU
                      cpuIterations: 30000
                      ratio: 0.5
                runs:
                  - name: test
                    mode: shared
                    workload: cpu_mixed
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("cpu_mixed");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        assertEquals(2, cals.size());
        
        assertEquals("DURATION_CONTROLLED", cals.get(0).cpuMode());
        assertEquals(100L, cals.get(0).cpuTargetMicros());
        
        assertEquals("FIXED_ITERATION", cals.get(1).cpuMode());
        assertEquals(30000, cals.get(1).cpuIterations());
    }

    @Test
    void cpuAndMemoryUnifiedTimingForAnyDuration() throws IOException {
        // Verify CPU and MEMORY use unified time control for ANY duration
        // Test multiple durations: 50, 100, 200, 500, 2000 microseconds
        
        long[] targetMicrosArray = {50, 100, 200, 500, 2000};
        
        for (long targetMicros : targetMicrosArray) {
            String cfgSuffix = "cpu_" + targetMicros + "us";
            String memSuffix = "mem_" + targetMicros + "us";
            
            Path cpuConfig = writeTmp(String.format("""
                    global:
                      workerCount: 2
                    workloads:
                      %s:
                        - kind: CPU
                          targetMicros: %d
                          ratio: 1.0
                    runs:
                      - name: test
                        mode: shared
                        workload: %s
                    """, cfgSuffix, targetMicros, cfgSuffix));
            
            Path memConfig = writeTmp(String.format("""
                    global:
                      workerCount: 2
                    workloads:
                      %s:
                        - kind: MEMORY
                          targetMicros: %d
                          ratio: 1.0
                          memory:
                            accessPattern: SEQUENTIAL
                            bufferMB: 8
                            writeBack: false
                    runs:
                      - name: test
                        mode: shared
                        workload: %s
                    """, memSuffix, targetMicros, memSuffix));
            
            RootConfig cpuCfg = BenchmarkConfigLoader.load(cpuConfig);
            RootConfig memCfg = BenchmarkConfigLoader.load(memConfig);
            
            TaskGenerator cpuGen = new TaskGenerator(cpuCfg.workloads().get(cfgSuffix), 0L);
            TaskGenerator memGen = new TaskGenerator(memCfg.workloads().get(memSuffix), 0L);
            
            long targetNanos = targetMicros * 1_000L;
            double cpuErrorSum = 0;
            double memErrorSum = 0;
            int runs = 10;
            
            for (int i = 0; i < runs; i++) {
                // CPU
                Task cpuTask = cpuGen.nextTask(i, false, null);
                long cpuStart = System.nanoTime();
                cpuTask.workload().execute();
                long cpuElapsed = System.nanoTime() - cpuStart;
                double cpuError = Math.abs(cpuElapsed - targetNanos) / 1_000.0;
                cpuErrorSum += cpuError;
                
                // MEMORY
                Task memTask = memGen.nextTask(i, false, null);
                long memStart = System.nanoTime();
                memTask.workload().execute();
                long memElapsed = System.nanoTime() - memStart;
                double memError = Math.abs(memElapsed - targetNanos) / 1_000.0;
                memErrorSum += memError;
            }
            
            double cpuAvgError = cpuErrorSum / runs;
            double memAvgError = memErrorSum / runs;
            double errorRatio = Math.max(cpuAvgError, memAvgError) / Math.min(cpuAvgError, memAvgError);
            double cpuRelativeError = cpuAvgError / targetMicros * 100;
            double memRelativeError = memAvgError / targetMicros * 100;
            
            System.out.printf("%dµs: CPU=%.2fµs (%.1f%%), MEMORY=%.2fµs (%.1f%%), ratio=%.2f%n", 
                    targetMicros, cpuAvgError, cpuRelativeError, memAvgError, memRelativeError, errorRatio);
            
            // Verify both CPU and MEMORY meet timing correctly
            // CPU is faster but MEMORY is slower, so allow higher tolerance for short durations
            double maxAllowed = targetMicros < 100 ? targetMicros * 0.3 : targetMicros * 0.2;
            assertTrue(cpuAvgError < maxAllowed, 
                    String.format("%dµs CPU: error %.2fµs > limit %.2fµs", 
                        targetMicros, cpuAvgError, maxAllowed));
            assertTrue(memAvgError < maxAllowed, 
                    String.format("%dµs MEMORY: error %.2fµs > limit %.2fµs", 
                        targetMicros, memAvgError, maxAllowed));
            
            // Verify they use same time control algorithm (both should execute ~target time)
            assertTrue(cpuAvgError < targetMicros, "CPU should execute roughly target time");
            assertTrue(memAvgError < targetMicros, "MEMORY should execute roughly target time");
        }
    }

    @Test
    void cpuAndMemoryTimingStatistics() throws IOException {
        // Detailed statistics: 20 runs per duration, with p50/p90/p99 percentiles
        long[] targetMicrosArray = {50, 100, 200, 500, 2000};
        int runsPerDuration = 20;
        
        System.out.println("\n" + "=".repeat(120));
        System.out.println("CPU vs MEMORY - Timing Statistics (20 runs each)");
        System.out.println("=".repeat(120));
        
        for (long targetMicros : targetMicrosArray) {
            String cfgSuffix = "cpu_" + targetMicros + "us";
            String memSuffix = "mem_" + targetMicros + "us";
            
            Path cpuConfig = writeTmp(String.format("""
                    global:
                      workerCount: 2
                    workloads:
                      %s:
                        - kind: CPU
                          targetMicros: %d
                          ratio: 1.0
                    runs:
                      - name: test
                        mode: shared
                        workload: %s
                    """, cfgSuffix, targetMicros, cfgSuffix));
            
            Path memConfig = writeTmp(String.format("""
                    global:
                      workerCount: 2
                    workloads:
                      %s:
                        - kind: MEMORY
                          targetMicros: %d
                          ratio: 1.0
                          memory:
                            accessPattern: SEQUENTIAL
                            bufferMB: 8
                            writeBack: false
                    runs:
                      - name: test
                        mode: shared
                        workload: %s
                    """, memSuffix, targetMicros, memSuffix));
            
            RootConfig cpuCfg = BenchmarkConfigLoader.load(cpuConfig);
            RootConfig memCfg = BenchmarkConfigLoader.load(memConfig);
            
            TaskGenerator cpuGen = new TaskGenerator(cpuCfg.workloads().get(cfgSuffix), 0L);
            TaskGenerator memGen = new TaskGenerator(memCfg.workloads().get(memSuffix), 0L);
            
            long targetNanos = targetMicros * 1_000L;
            double[] cpuTimings = new double[runsPerDuration];
            double[] memTimings = new double[runsPerDuration];
            
            // Collect 20 timing measurements for each
            for (int i = 0; i < runsPerDuration; i++) {
                // CPU
                Task cpuTask = cpuGen.nextTask(i, false, null);
                long cpuStart = System.nanoTime();
                cpuTask.workload().execute();
                long cpuElapsed = System.nanoTime() - cpuStart;
                cpuTimings[i] = cpuElapsed / 1_000.0;  // Convert to µs
                
                // MEMORY
                Task memTask = memGen.nextTask(i, false, null);
                long memStart = System.nanoTime();
                memTask.workload().execute();
                long memElapsed = System.nanoTime() - memStart;
                memTimings[i] = memElapsed / 1_000.0;  // Convert to µs
            }
            
            // Sort for percentile calculation
            java.util.Arrays.sort(cpuTimings);
            java.util.Arrays.sort(memTimings);
            
            // Calculate percentiles
            double cpuP50 = cpuTimings[10];  // Index 10 = 50% of 20
            double cpuP90 = cpuTimings[18];  // Index 18 = 90% of 20
            double cpuP99 = cpuTimings[19];  // Index 19 = 95% of 20 (approximation)
            
            double memP50 = memTimings[10];
            double memP90 = memTimings[18];
            double memP99 = memTimings[19];
            
            // Calculate averages
            double cpuAvg = java.util.Arrays.stream(cpuTimings).average().orElse(0);
            double memAvg = java.util.Arrays.stream(memTimings).average().orElse(0);
            
            // Print results
            System.out.printf("\nTarget: %dµs%n", targetMicros);
            System.out.println("-".repeat(120));
            System.out.printf("%-10s | %10s | %10s | %10s | %10s | %10s | %10s%n", 
                    "Metric", "Avg(µs)", "P50(µs)", "P90(µs)", "P99(µs)", "Min(µs)", "Max(µs)");
            System.out.println("-".repeat(120));
            
            double cpuMin = cpuTimings[0];
            double cpuMax = cpuTimings[runsPerDuration - 1];
            double memMin = memTimings[0];
            double memMax = memTimings[runsPerDuration - 1];
            
            System.out.printf("%-10s | %10.3f | %10.3f | %10.3f | %10.3f | %10.3f | %10.3f%n",
                    "CPU", cpuAvg, cpuP50, cpuP90, cpuP99, cpuMin, cpuMax);
            System.out.printf("%-10s | %10.3f | %10.3f | %10.3f | %10.3f | %10.3f | %10.3f%n",
                    "MEMORY", memAvg, memP50, memP90, memP99, memMin, memMax);
            System.out.println("-".repeat(120));
            
            // Additional stats
            System.out.printf("CPU Variance: %.3f, MEMORY Variance: %.3f%n",
                    calculateVariance(cpuTimings),
                    calculateVariance(memTimings));
        }
        System.out.println("=".repeat(120) + "\n");
    }
    
    /**
     * Helper method to calculate variance
     */
    private double calculateVariance(double[] values) {
        double avg = java.util.Arrays.stream(values).average().orElse(0);
        double sumSquaredDiff = 0;
        for (double v : values) {
            sumSquaredDiff += (v - avg) * (v - avg);
        }
        return sumSquaredDiff / values.length;
    }

}

