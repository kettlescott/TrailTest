package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkloadEntry Duration File Mode Tests")
class WorkloadEntryDurationFileModeTest {

    @Test
    @DisplayName("Should create entry with durationFile and ratio 1.0")
    void testValidDurationFileEntry() {
        WorkloadEntry entry = new WorkloadEntry(
            "file_test", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 0, "durations.txt");
        
        assertTrue(entry.usesDurationFile());
        assertEquals("durations.txt", entry.durationFile());
        assertEquals(1.0, entry.ratio());
    }

    @Test
    @DisplayName("Should reject durationFile with ratio != 1.0")
    void testRejectInvalidRatioWithFile() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkloadEntry("bad", WorkloadKind.CPU, 0, 0.5, null, 0, 0, 0, "durations.txt"));
    }

    @Test
    @DisplayName("Should reject durationFile with targetMicros")
    void testRejectTargetMicrosWithFile() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkloadEntry("bad", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 100L, "durations.txt"));
    }

    @Test
    @DisplayName("Should reject durationFile with targetMillis")
    void testRejectTargetMillisWithFile() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkloadEntry("bad", WorkloadKind.CPU, 50L, 1.0, null, 0, 0, 0, "durations.txt"));
    }

    @Test
    @DisplayName("Should reject durationFile with cpuIterations")
    void testRejectCpuIterationsWithFile() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkloadEntry("bad", WorkloadKind.CPU, 0, 1.0, null, 100, 0, 0, "durations.txt"));
    }

    @Test
    @DisplayName("Should reject durationFile with memorySteps")
    void testRejectMemoryStepsWithFile() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkloadEntry("bad", WorkloadKind.MEMORY, 0, 1.0, null, 0, 10, 0, "durations.txt"));
    }

    @Test
    @DisplayName("Should allow MEMORY with durationFile")
    void testMemoryWithDurationFile() {
        WorkloadEntry entry = new WorkloadEntry(
            "mem_file", WorkloadKind.MEMORY, 0, 1.0, null, 0, 0, 0, "mem_durations.txt");
        
        assertTrue(entry.usesDurationFile());
        assertEquals(WorkloadKind.MEMORY, entry.kind());
    }

    @Test
    @DisplayName("Should return false for usesDurationFile when not set")
    void testUsesDurationFileReturnsFalse() {
        WorkloadEntry entry = new WorkloadEntry(
            "normal", WorkloadKind.CPU, 50L, 1.0, null, 0, 0, 0, null);
        
        assertFalse(entry.usesDurationFile());
    }

    @Test
    @DisplayName("Should return false for blank durationFile")
    void testBlankDurationFileNotUsed() {
        WorkloadEntry entry = new WorkloadEntry(
            "blank", WorkloadKind.CPU, 50L, 1.0, null, 0, 0, 0, "");
        
        assertFalse(entry.usesDurationFile());
    }

    @Test
    @DisplayName("Should parse from YAML map with durationFile")
    void testParseFromMapWithFile() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("kind", "CPU");
        map.put("durationFile", "durations.txt");
        map.put("ratio", 1.0);
        
        WorkloadEntry entry = WorkloadEntry.fromMap(map, "test");
        
        assertTrue(entry.usesDurationFile());
        assertEquals("durations.txt", entry.durationFile());
    }

    @Test
    @DisplayName("Should parse ratio as 1.0 by default with durationFile")
    void testDefaultRatioWithFile() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("kind", "MEMORY");
        map.put("durationFile", "mem.txt");
        
        WorkloadEntry entry = WorkloadEntry.fromMap(map, "test");
        assertEquals(1.0, entry.ratio());
    }
}

