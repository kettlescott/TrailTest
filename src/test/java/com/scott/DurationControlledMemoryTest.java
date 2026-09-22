package com.scott;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for duration-controlled MEMORY workload mode.
 */
class DurationControlledMemoryTest {

    private static Path writeTmp(String yaml) throws IOException {
        Path p = Files.createTempFile("duration-mem-test-", ".yaml");
        Files.writeString(p, yaml);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void parsesDurationControlledMemoryEntry() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 8
                workloads:
                  mem_50us:
                    - kind: MEMORY
                      targetMicros: 50
                      ratio: 1.0
                      memory:
                        accessPattern: RANDOM
                        bufferMB: 64
                        writeBack: false
                runs:
                  - name: test_mem_50us
                    mode: shared
                    workload: mem_50us
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        assertNotNull(r);
        assertEquals(1, r.runs().size());
        
        RunConfig run = r.runs().get(0);
        WorkloadConfig workload = r.workloads().get("mem_50us");
        WorkloadEntry entry = workload.entries().get(0);
        
        assertEquals(50L, entry.targetMicros());
        assertEquals(WorkloadKind.MEMORY, entry.kind());
        assertEquals(64, entry.memoryOrDefaults().bufferMB());
        assertEquals(MemoryBoundWorkload.AccessPattern.RANDOM, entry.memoryOrDefaults().accessPattern());
    }

    @Test
    void durationControlledMemoryInitializesCorrectly() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 4
                workloads:
                  mem_100us:
                    - kind: MEMORY
                      targetMicros: 100
                      ratio: 1.0
                      memory:
                        accessPattern: SEQUENTIAL
                        bufferMB: 8
                        writeBack: false
                runs:
                  - name: test_mem_100us
                    mode: shared
                    workload: mem_100us
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("mem_100us");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        // Check calibration records
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        assertEquals(1, cals.size());
        
        TaskGenerator.Calibration c = cals.get(0);
        assertEquals(WorkloadKind.MEMORY, c.kind());
        assertEquals(100L, c.targetMicros());
        assertEquals("DURATION_CONTROLLED", c.memoryMode());
        assertEquals(8, c.memoryBufferMB());
        assertEquals(MemoryBoundWorkload.AccessPattern.SEQUENTIAL, c.memoryAccessPattern());
    }

    @Test
    void fixedStepModePreservesOldBehavior() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 4
                workloads:
                  mem_fixed:
                    - kind: MEMORY
                      memorySteps: 320
                      ratio: 1.0
                      memory:
                        accessPattern: RANDOM
                        bufferMB: 64
                        writeBack: false
                runs:
                  - name: test_mem_fixed
                    mode: shared
                    workload: mem_fixed
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("mem_fixed");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        assertEquals(1, cals.size());
        
        TaskGenerator.Calibration c = cals.get(0);
        assertEquals(WorkloadKind.MEMORY, c.kind());
        assertEquals(320, c.memorySteps());
        assertEquals("FIXED_STEP", c.memoryMode());
        assertEquals(0L, c.targetMicros());
    }

    @Test
    void targetMicrosPrecedesMemorySteps() throws IOException {
        // When both targetMicros and memorySteps are set, targetMicros wins
        Path p = writeTmp("""
                global:
                  workerCount: 4
                workloads:
                  mem_both:
                    - kind: MEMORY
                      targetMicros: 50
                      memorySteps: 320
                      ratio: 1.0
                      memory:
                        accessPattern: RANDOM
                        bufferMB: 64
                        writeBack: false
                runs:
                  - name: test_mem_both
                    mode: shared
                    workload: mem_both
                """);
        RootConfig r = BenchmarkConfigLoader.load(p);
        WorkloadConfig workload = r.workloads().get("mem_both");
        TaskGenerator gen = new TaskGenerator(workload, 0L);
        
        java.util.List<TaskGenerator.Calibration> cals = gen.calibrations();
        TaskGenerator.Calibration c = cals.get(0);
        
        // targetMicros should take precedence
        assertEquals("DURATION_CONTROLLED", c.memoryMode());
        assertEquals(50L, c.targetMicros());
    }

    @Test
    void durationControlledWorkloadExecutes() {
        long[] buffer = new long[64 * (1 << 17)]; // 64 MiB
        java.util.Random rand = new java.util.Random(42);
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = rand.nextLong();
        }
        
        // Create a short-duration workload (50 microseconds)
        MemoryBoundWorkloadDuration workload = new MemoryBoundWorkloadDuration(
                buffer, 50L, MemoryBoundWorkload.AccessPattern.RANDOM, 12345L, false, 8);
        
        long start = System.nanoTime();
        long result = workload.execute();
        long elapsed = System.nanoTime() - start;
        
        // Task should complete
        assertNotEquals(0L, result);
        
        // Execution time should be close to 50 microseconds (within 1ms tolerance)
        long elapsedMicros = elapsed / 1_000L;
        assertTrue(elapsedMicros >= 40L, "Execution too fast: " + elapsedMicros + " micros");
        assertTrue(elapsedMicros <= 2000L, "Execution too slow: " + elapsedMicros + " micros");
    }
}

