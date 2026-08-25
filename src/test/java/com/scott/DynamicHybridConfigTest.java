package com.scott;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** YAML parsing + backward-compat + validation tests for Dynamic Hybrid. */
class DynamicHybridConfigTest {

    private static Path writeTmp(String yaml) throws IOException {
        Path p = Files.createTempFile("dynhybrid-test-", ".yaml");
        Files.writeString(p, yaml);
        p.toFile().deleteOnExit();
        return p;
    }

    private static final String WORKLOAD_BLOCK = """
            workloads:
              w1:
                - kind: CPU
                  targetMillis: 1
                  ratio: 1.0
            """;

    // ------------------------------------------------------------------
    // TEST 1 — YAML parsing of a Dynamic Hybrid configuration.
    // ------------------------------------------------------------------
    @Test
    void parsesDynamicHybridBlock() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 32
                %s
                dynamicHybrid:
                  crossoverThresholdMicros: 200
                  minShardedWorkers: 8
                  ewmaAlpha: 0.2
                  scaleOutThresholdMicros: 500
                  scaleInThresholdMicros: 150
                  controllerIntervalMicros: 100
                runs:
                  - name: dh1
                    mode: dynamic_hybrid
                    workload: w1
                """.formatted(WORKLOAD_BLOCK));
        RootConfig r = BenchmarkConfigLoader.load(p);
        DynamicHybridConfig c = r.dynamicHybrid();
        assertNotNull(c);
        assertEquals(200L,  c.crossoverThresholdMicros());
        assertEquals(8,     c.minShardedWorkers());
        assertEquals(0.2,   c.ewmaAlpha(), 1e-9);
        assertEquals(500L,  c.scaleOutThresholdMicros());
        assertEquals(150L,  c.scaleInThresholdMicros());
        assertEquals(100L,  c.controllerIntervalMicros());
    }

    // ------------------------------------------------------------------
    // TEST 2 — Existing SHARED YAML without a dynamicHybrid block still loads.
    // ------------------------------------------------------------------
    @Test
    void sharedConfigWithoutDynamicHybridBlockStillLoads() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 8
                %s
                runs:
                  - name: s1
                    mode: shared
                    workload: w1
                """.formatted(WORKLOAD_BLOCK));
        RootConfig r = BenchmarkConfigLoader.load(p);
        assertNull(r.dynamicHybrid());
        assertEquals(1, r.runs().size());
        assertEquals("shared", r.runs().get(0).mode());
    }

    // ------------------------------------------------------------------
    // TEST 3 — Existing SHARDED YAML without a dynamicHybrid block still loads.
    // ------------------------------------------------------------------
    @Test
    void shardedConfigWithoutDynamicHybridBlockStillLoads() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 8
                %s
                runs:
                  - name: sh1
                    mode: sharded
                    workload: w1
                """.formatted(WORKLOAD_BLOCK));
        RootConfig r = BenchmarkConfigLoader.load(p);
        assertNull(r.dynamicHybrid());
    }

    // ------------------------------------------------------------------
    // TEST 4 — Missing block when mode=dynamic_hybrid is a validation error.
    // ------------------------------------------------------------------
    @Test
    void dynamicHybridRunWithoutBlockFails() throws IOException {
        Path p = writeTmp("""
                global:
                  workerCount: 8
                %s
                runs:
                  - name: dh1
                    mode: dynamic_hybrid
                    workload: w1
                """.formatted(WORKLOAD_BLOCK));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkConfigLoader.load(p));
        assertTrue(ex.getMessage().contains("dynamicHybrid"),
                "message should mention dynamicHybrid: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // TEST 5 — Direct DynamicHybridConfig.validate() rejects invalid values.
    // ------------------------------------------------------------------
    @Test
    void validationRejectsNminGreaterThanN() {
        DynamicHybridConfig c = new DynamicHybridConfig(200, 10, 1, 0.2, 500, 150, 100);
        assertThrows(IllegalArgumentException.class, () -> c.validate(8));
    }

    @Test
    void validationRejectsNegativeNmin() {
        DynamicHybridConfig c = new DynamicHybridConfig(200, -1, 1, 0.2, 500, 150, 100);
        assertThrows(IllegalArgumentException.class, () -> c.validate(8));
    }

    @Test
    void validationRejectsAlphaOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(200, 2, 1, 0.0, 500, 150, 100).validate(8));
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(200, 2, 1, 1.5, 500, 150, 100).validate(8));
    }

    @Test
    void validationRejectsHLeqL() {
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(200, 2, 1, 0.2, 150, 150, 100).validate(8));
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(200, 2, 1, 0.2, 100, 150, 100).validate(8));
    }

    @Test
    void validationRejectsNegativeTc() {
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(-1, 2, 1, 0.2, 500, 150, 100).validate(8));
    }

    @Test
    void validationRejectsNonPositiveControllerInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridConfig(200, 2, 1, 0.2, 500, 150, 0).validate(8));
    }
}

