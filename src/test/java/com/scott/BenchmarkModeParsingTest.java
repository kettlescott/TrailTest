package com.scott;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkModeParsingTest {
    @Test
    void parsesDynamicHybridFromConfig() {
        assertEquals(BenchmarkMode.DYNAMIC_HYBRID, BenchmarkMode.fromConfigValue("dynamic_hybrid"));
        assertEquals(BenchmarkMode.DYNAMIC_HYBRID, BenchmarkMode.fromConfigValue("dynamic-hybrid"));
        assertEquals(BenchmarkMode.DYNAMIC_HYBRID, BenchmarkMode.fromConfigValue("DYNAMIC_HYBRID"));
    }
    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkMode.fromConfigValue("bogus"));
    }
}

