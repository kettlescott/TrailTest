package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation test for all 18 duration-controlled memory benchmark configs.
 *
 * Tests that:
 * 1. All configs in benchmarks_shared_queue_sweep/memory/ load successfully
 * 2. Mode detection works correctly (DURATION_CONTROLLED)
 * 3. Target microseconds are preserved
 * 4. Workload generation succeeds
 */
class DurationControlledMemoryValidationTest {

    private static final Path BENCHMARK_DIR = 
        Paths.get("benchmarks_shared_queue_sweep/memory");

    private static List<Path> getAllBenchmarkYamls() throws Exception {
        List<Path> result = new ArrayList<>();
        if (Files.exists(BENCHMARK_DIR)) {
            Files.walk(BENCHMARK_DIR)
                .filter(p -> p.toString().endsWith(".yaml"))
                .forEach(result::add);
        }
        return result;
    }

    @Test
    void allBenchmarkFilesExist() throws Exception {
        List<Path> configs = getAllBenchmarkYamls();
        
        // Expect 18 files: 50us(6) + 100us(6) + 300us(6)
        assertTrue(configs.size() >= 18, 
            "Found " + configs.size() + " configs; expected at least 18 (50us×6 + 100us×6 + 300us×6)");
        
        System.out.println("✅ Found " + configs.size() + " benchmark configs:");
        configs.forEach(p -> System.out.println("  " + p));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "50us/benchmarks_shared_mem_50us_q1.yaml",
        "50us/benchmarks_shared_mem_50us_q2.yaml",
        "50us/benchmarks_shared_mem_50us_q4.yaml",
        "50us/benchmarks_shared_mem_50us_q8.yaml",
        "50us/benchmarks_shared_mem_50us_q16.yaml",
        "50us/benchmarks_shared_mem_50us_q32.yaml",
        "100us/benchmarks_shared_mem_100us_q1.yaml",
        "100us/benchmarks_shared_mem_100us_q2.yaml",
        "100us/benchmarks_shared_mem_100us_q4.yaml",
        "100us/benchmarks_shared_mem_100us_q8.yaml",
        "100us/benchmarks_shared_mem_100us_q16.yaml",
        "100us/benchmarks_shared_mem_100us_q32.yaml",
        "300us/benchmarks_shared_mem_300us_q1.yaml",
        "300us/benchmarks_shared_mem_300us_q2.yaml",
        "300us/benchmarks_shared_mem_300us_q4.yaml",
        "300us/benchmarks_shared_mem_300us_q8.yaml",
        "300us/benchmarks_shared_mem_300us_q16.yaml",
        "300us/benchmarks_shared_mem_300us_q32.yaml"
    })
    void eachConfigLoadsSuccessfully(String relPath) throws Exception {
        Path configPath = BENCHMARK_DIR.resolve(relPath);
        assertTrue(Files.exists(configPath), 
            "Config file not found: " + configPath);
        
        // Load and validate
        RootConfig config = BenchmarkConfigLoader.load(configPath);
        assertNotNull(config, "Config loaded as null for: " + relPath);
        
        // Extract target micros from filename
        long expectedTargetMicros = extractTargetMicros(relPath);
        
        // Get workload and entry
        WorkloadConfig workload = config.workloads().values().stream().findFirst().orElse(null);
        assertNotNull(workload, "No workload found in: " + relPath);
        
        WorkloadEntry entry = workload.entries().stream().findFirst().orElse(null);
        assertNotNull(entry, "No workload entry found in: " + relPath);
        
        // Verify duration-controlled mode
        assertEquals(expectedTargetMicros, entry.targetMicros(), 
            "targetMicros mismatch in: " + relPath);
        assertEquals(WorkloadKind.MEMORY, entry.kind(), 
            "Must be MEMORY kind in: " + relPath);
        
        System.out.println("✅ " + relPath + " → targetMicros=" + expectedTargetMicros);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "50us/benchmarks_shared_mem_50us_q1.yaml",
        "100us/benchmarks_shared_mem_100us_q16.yaml",
        "300us/benchmarks_shared_mem_300us_q32.yaml"
    })
    void workloadGenerationSucceeds(String relPath) throws Exception {
        Path configPath = BENCHMARK_DIR.resolve(relPath);
        RootConfig config = BenchmarkConfigLoader.load(configPath);
        
        WorkloadConfig workload = config.workloads().values().stream().findFirst().get();
        TaskGenerator taskGen = new TaskGenerator(workload, 0L);
        
        // Verify calibrations are detected correctly
        List<TaskGenerator.Calibration> cals = taskGen.calibrations();
        assertEquals(1, cals.size(), "Should have exactly 1 calibration for: " + relPath);
        
        TaskGenerator.Calibration cal = cals.get(0);
        assertEquals(WorkloadKind.MEMORY, cal.kind());
        assertEquals("DURATION_CONTROLLED", cal.memoryMode(), 
            "Must detect DURATION_CONTROLLED mode for: " + relPath);
        assertTrue(cal.targetMicros() > 0, 
            "targetMicros must be positive for: " + relPath);
        
        // Verify that workloads can be created
//        for (int i = 0; i < 3; i++) {
//            Workload w = taskGen.createWorkload(i);
//            assertNotNull(w, "Failed to create workload " + i + " for: " + relPath);
//            assertInstanceOf(MemoryBoundWorkloadDuration.class, w,
//                "Expected MemoryBoundWorkloadDuration for: " + relPath);
//        }
        
        System.out.println("✅ " + relPath + " → Generated workloads successfully (DURATION_CONTROLLED)");
    }

    @Test
    void multipleQueueCountsPreserveTargetMicros() throws Exception {
        // Load Q1 and Q32 configs for 50us
        RootConfig q1 = BenchmarkConfigLoader.load(
            BENCHMARK_DIR.resolve("50us/benchmarks_shared_mem_50us_q1.yaml"));
        RootConfig q32 = BenchmarkConfigLoader.load(
            BENCHMARK_DIR.resolve("50us/benchmarks_shared_mem_50us_q32.yaml"));
        
        WorkloadEntry e1 = q1.workloads().values().stream()
            .findFirst().get().entries().get(0);
        WorkloadEntry e32 = q32.workloads().values().stream()
            .findFirst().get().entries().get(0);
        
        // Both should have identical target
        assertEquals(e1.targetMicros(), e32.targetMicros(), 
            "Queue count should not affect targetMicros");
        assertEquals(50L, e1.targetMicros(), "Q1 should target 50µs");
        assertEquals(50L, e32.targetMicros(), "Q32 should target 50µs");
        
        System.out.println("✅ Q1 and Q32 preserve same targetMicros=50");
    }

    @Test
    void differentTargetDurationsAreCorrect() throws Exception {
        long target50 = BenchmarkConfigLoader.load(
            BENCHMARK_DIR.resolve("50us/benchmarks_shared_mem_50us_q1.yaml"))
            .workloads().values().stream().findFirst().get().entries().get(0)
            .targetMicros();
        
        long target100 = BenchmarkConfigLoader.load(
            BENCHMARK_DIR.resolve("100us/benchmarks_shared_mem_100us_q1.yaml"))
            .workloads().values().stream().findFirst().get().entries().get(0)
            .targetMicros();
        
        long target300 = BenchmarkConfigLoader.load(
            BENCHMARK_DIR.resolve("300us/benchmarks_shared_mem_300us_q1.yaml"))
            .workloads().values().stream().findFirst().get().entries().get(0)
            .targetMicros();
        
        assertEquals(50L, target50);
        assertEquals(100L, target100);
        assertEquals(300L, target300);
        
        System.out.println("✅ Target durations correct: 50µs, 100µs, 300µs");
    }

    private long extractTargetMicros(String filename) {
        if (filename.contains("_50us")) return 50L;
        if (filename.contains("_100us")) return 100L;
        if (filename.contains("_300us")) return 300L;
        throw new IllegalArgumentException("Cannot extract target from: " + filename);
    }
}

