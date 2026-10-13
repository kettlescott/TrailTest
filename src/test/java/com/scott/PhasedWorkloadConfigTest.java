package com.scott;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for phased workload YAML parsing.
 */
class PhasedWorkloadConfigTest {

    private static Path writeTmp(String yaml) throws IOException {
        Path p = Files.createTempFile("phased-test-", ".yaml");
        Files.writeString(p, yaml);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void parsesPhaseWorkloadWithHybridExecutor() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 32
                  maxInflight: 32
                  hybrid: true
                
                workloads:
                  cpu_dummy:
                    - kind: CPU
                      targetMillis: 1
                      ratio: 1.0
                
                hybridworkloads:
                  cpu_50us:
                    kind: CPU
                    executor: HYBRID
                    targetMicros: 50
                    hybrid:
                      initialMode: SHARDED
                      sharedEnterCv: 0.16
                      shardedEnterCv: 0.08
                      windowMillis: 100
                    phases:
                      - durationSeconds: 30
                        durationFile: durations/phase1.txt
                      - durationSeconds: 120
                        durationFile: durations/phase2.txt
                      - durationSeconds: 30
                        durationFile: durations/phase1.txt
                
                runs:
                  - name: test_phased_hybrid
                    mode: hybrid
                    workload: cpu_50us
                """);
        
        RootConfig root = BenchmarkConfigLoader.load(p);
        
        // Check global.hybrid is true
        assertTrue(root.global().hybrid());
        
        // Check hybridworkloads was parsed
        assertNotNull(root.hybridworkloads());
        assertTrue(root.hybridworkloads().containsKey("cpu_50us"));
        
        PhasedWorkloadEntry entry = root.hybridworkloads().get("cpu_50us");
        assertEquals("cpu_50us", entry.name());
        assertEquals(WorkloadKind.CPU, entry.kind());
        assertEquals(ExecutorMode.HYBRID, entry.executor());
        assertEquals(50, entry.targetMicros());
        
        // Check hybrid config
        assertNotNull(entry.hybrid());
        assertEquals(ExecutorMode.SHARDED, entry.hybrid().initialMode());
        assertEquals(0.16, entry.hybrid().sharedEnterCv(), 1e-9);
        assertEquals(0.08, entry.hybrid().shardedEnterCv(), 1e-9);
        assertEquals(100, entry.hybrid().windowMillis());
        
        // Check phases
        assertEquals(3, entry.phases().size());
        assertEquals(30, entry.phases().get(0).durationSeconds());
        assertEquals("durations/phase1.txt", entry.phases().get(0).durationFile());
        assertEquals(120, entry.phases().get(1).durationSeconds());
        assertEquals("durations/phase2.txt", entry.phases().get(1).durationFile());
    }

    @Test
    void parsesPhaseWorkloadWithSharedExecutor() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 16
                  hybrid: true
                
                workloads:
                  dummy:
                    - kind: CPU
                      targetMillis: 1
                      ratio: 1.0
                
                hybridworkloads:
                  mem_test:
                    kind: MEMORY
                    executor: SHARED
                    targetMicros: 1000
                    phases:
                      - durationSeconds: 60
                        durationFile: durations/mem.txt
                
                runs:
                  - name: test_phased_shared
                    mode: sharded
                    workload: mem_test
                """);
        
        RootConfig root = BenchmarkConfigLoader.load(p);
        assertTrue(root.global().hybrid());
        
        PhasedWorkloadEntry entry = root.hybridworkloads().get("mem_test");
        assertEquals(ExecutorMode.SHARED, entry.executor());
        assertEquals(WorkloadKind.MEMORY, entry.kind());
        assertNull(entry.hybrid());  // SHARED doesn't need hybrid config
    }

    @Test
    void rejectsHybridExecutorWithoutHybridConfig() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 32
                  hybrid: true
                
                workloads:
                  dummy:
                    - kind: CPU
                      targetMillis: 1
                      ratio: 1.0
                
                hybridworkloads:
                  bad_hybrid:
                    kind: CPU
                    executor: HYBRID
                    targetMicros: 50
                    phases:
                      - durationSeconds: 60
                        durationFile: durations/cpu.txt
                
                runs:
                  - name: test
                    mode: shared
                    workload: bad_hybrid
                """);
        
        assertThrows(IllegalArgumentException.class, () -> BenchmarkConfigLoader.load(p),
                "Should reject HYBRID executor without hybrid config");
    }

    @Test
    void rejectsEmptyPhases() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 32
                  hybrid: true
                
                workloads:
                  dummy:
                    - kind: CPU
                      targetMillis: 1
                      ratio: 1.0
                
                hybridworkloads:
                  no_phases:
                    kind: CPU
                    executor: SHARDED
                    phases: []
                
                runs:
                  - name: test
                    mode: shared
                    workload: no_phases
                """);
        
        assertThrows(IllegalArgumentException.class, () -> BenchmarkConfigLoader.load(p),
                "Should reject empty phases list");
    }

    @Test
    void legacyModeIgnoresHybridworkloads() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 32
                  hybrid: false
                
                workloads:
                  cpu_test:
                    - kind: CPU
                      targetMillis: 5
                      ratio: 1.0
                
                runs:
                  - name: legacy_run
                    mode: sharded
                    workload: cpu_test
                """);
        
        RootConfig root = BenchmarkConfigLoader.load(p);
        assertFalse(root.global().hybrid());
        assertTrue(root.hybridworkloads().isEmpty());
    }
}
