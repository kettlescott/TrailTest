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
    void cpuDurationControlledDeterministic() {
        // Same seed should produce same result
        CpuBoundWorkloadDuration w1 = new CpuBoundWorkloadDuration(100, 12345, 100);
        CpuBoundWorkloadDuration w2 = new CpuBoundWorkloadDuration(100, 12345, 100);
        
        long r1 = w1.execute();
        long r2 = w2.execute();
        
        assertEquals(r1, r2, "Same seed should produce same result");
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

}

