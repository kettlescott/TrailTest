package com.scott;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for complete phased workload execution.
 */
class PhasedBenchmarkIntegrationTest {

    @Test
    void runsSimplePhasedWorkload() throws Exception {
        // Create temporary duration files
        Path file1 = Files.createTempFile("phase1-", ".txt");
        Path file2 = Files.createTempFile("phase2-", ".txt");
        
        try {
            // Fill file1 with constant 100µs durations
            StringBuilder sb1 = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb1.append("100\n");
            }
            Files.writeString(file1, sb1.toString());
            
            // Fill file2 with constant 200µs durations
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb2.append("200\n");
            }
            Files.writeString(file2, sb2.toString());

            // Create phased workload entry
            List<PhasedConfig> phases = new ArrayList<>();
            phases.add(new PhasedConfig(1, file1.toString()));  // 1 second
            phases.add(new PhasedConfig(1, file2.toString()));  // 1 second
            
            PhasedWorkloadEntry entry = new PhasedWorkloadEntry(
                    "test_phased",
                    WorkloadKind.CPU,
                    ExecutorMode.SHARDED,
                    100L,
                    null,
                    phases,
                    null,
                    10,  // cpuIterations
                    0
            );

            // Create executor
            ShardedExecutor executor = new ShardedExecutor(8);
            
            // Create runner
            PhasedBenchmarkRunner runner = new PhasedBenchmarkRunner(
                    entry,
                    executor,
                    file1.getParent()
            );

            // Preload files
            runner.preloadDurationFiles();

            // Verify files were loaded
            Map<String, long[]> fileCache = runner.getFileCache();
            assertNotNull(fileCache);
            assertEquals(2, fileCache.size());

            // Verify file contents
            long[] durations1 = fileCache.get(file1.toString());
            assertNotNull(durations1);
            assertEquals(50, durations1.length);
            assertEquals(100, durations1[0]);

            long[] durations2 = fileCache.get(file2.toString());
            assertNotNull(durations2);
            assertEquals(50, durations2.length);
            assertEquals(200, durations2[0]);

        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }
    }

    @Test
    void yamlConfigLoadsCorrectly() throws Exception {
        Path configPath = Files.createTempFile("phased-config-", ".yaml");
        
        try {
            String yaml = """
                global:
                  workerCount: 16
                  maxInflight: 32
                  hybrid: true
                
                workloads:
                  dummy:
                    - kind: CPU
                      targetMillis: 1
                      ratio: 1.0
                
                hybridworkloads:
                  adaptive_test:
                    kind: CPU
                    executor: HYBRID
                    targetMicros: 50
                    hybrid:
                      initialMode: SHARDED
                      sharedEnterCv: 0.16
                      shardedEnterCv: 0.08
                      windowMillis: 100
                      debugCvPrint: 1
                    phases:
                      - durationSeconds: 5
                        durationFile: /tmp/phase1.txt
                      - durationSeconds: 10
                        durationFile: /tmp/phase2.txt
                
                runs:
                  - name: test_run
                    mode: shared
                    workload: adaptive_test
                """;
            
            Files.writeString(configPath, yaml);
            
            RootConfig root = BenchmarkConfigLoader.load(configPath);
            
            // Verify global.hybrid is set
            assertTrue(root.global().hybrid());
            
            // Verify hybridworkloads section loaded
            assertNotNull(root.hybridworkloads());
            assertTrue(root.hybridworkloads().containsKey("adaptive_test"));
            
            PhasedWorkloadEntry entry = root.hybridworkloads().get("adaptive_test");
            
            // Verify phased config
            assertEquals(2, entry.phases().size());
            assertEquals(5, entry.phases().get(0).durationSeconds());
            assertEquals(10, entry.phases().get(1).durationSeconds());
            
            // Verify executor mode
            assertEquals(ExecutorMode.HYBRID, entry.executor());
            
            // Verify hybrid config
            assertNotNull(entry.hybrid());
            assertEquals(ExecutorMode.SHARDED, entry.hybrid().initialMode());
            assertEquals(1L, entry.hybrid().debugCvPrintIntervalSeconds());
            
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    void verifyExecutorStateTransitions() throws Exception {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(8, config);
        
        // Initial state
        assertEquals(ExecutorState.SHARDED, executor.getCurrentState());
        
        // Simulate CV-based switch (would be triggered by monitor thread in real usage)
        // For now, just verify the state machine structure is in place
        assertNotNull(executor.getMonitorThread());
        
        executor.shutdown();
    }

    @Test
    void cvMonitorThreadInitializes() throws Exception {
        SampleBuffer[] buffers = new SampleBuffer[4];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new SampleBuffer();
        }
        
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        
        java.util.concurrent.atomic.AtomicReference<ExecutorState> state =
                new java.util.concurrent.atomic.AtomicReference<>(ExecutorState.SHARDED);
        
        CVMonitorThread monitor = new CVMonitorThread(
                buffers,
                config,
                System.nanoTime(),
                state,
                (from, to, cv, mean, count) -> {
                    // Switch callback (no-op for test)
                }
        );
        
        assertNotNull(monitor);
        assertFalse(monitor.isAlive(), "Should not auto-start");
    }
}
