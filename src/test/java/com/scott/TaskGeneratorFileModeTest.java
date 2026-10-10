package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TaskGenerator File Mode Tests")
class TaskGeneratorFileModeTest {

    @Test
    @DisplayName("Should detect file mode and load tasks")
    void testLoadTasksFromFile(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50", "100", "200"));
        
        WorkloadEntry entry = new WorkloadEntry(
            "file", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 0, durFile.toString());
        WorkloadConfig config = new WorkloadConfig(List.of(entry));
        
        TaskGenerator gen = new TaskGenerator(config, 12345L);
        
        assertTrue(gen.isFileModeActive());
        assertEquals(3, gen.fileModeTaskCount());
    }

    @Test
    @DisplayName("Should generate CPU tasks from file")
    void testGenerateCpuTasksFromFile(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50", "100"));
        
        WorkloadEntry entry = new WorkloadEntry(
            "file", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 0, durFile.toString());
        WorkloadConfig config = new WorkloadConfig(List.of(entry));
        
        TaskGenerator gen = new TaskGenerator(config, 12345L);
        
        Task t1 = gen.nextTask(0, false, () -> {});
        assertNotNull(t1);
        assertEquals(WorkloadKind.CPU, t1.kind());
        
        Task t2 = gen.nextTask(1, false, () -> {});
        assertNotNull(t2);
        
        Task t3 = gen.nextTask(2, false, () -> {});
        assertNull(t3, "Should return null after EOF");
    }

    @Test
    @DisplayName("Should generate MEMORY tasks from file")
    void testGenerateMemoryTasksFromFile(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50", "100"));
        
        WorkloadEntry entry = new WorkloadEntry(
            "mem_file", WorkloadKind.MEMORY, 0, 1.0, null, 0, 0, 0, durFile.toString());
        WorkloadConfig config = new WorkloadConfig(List.of(entry));
        
        TaskGenerator gen = new TaskGenerator(config, 12345L);
        
        Task t1 = gen.nextTask(0, false, () -> {});
        assertNotNull(t1);
        assertEquals(WorkloadKind.MEMORY, t1.kind());
    }

    @Test
    @DisplayName("Should reject IO tasks in file mode")
    void testRejectIoTasksInFileMode(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50"));
        
        WorkloadEntry entry = new WorkloadEntry(
            "io_file", WorkloadKind.IO, 0, 1.0, null, 0, 0, 0, durFile.toString());
        WorkloadConfig config = new WorkloadConfig(List.of(entry));
        
        TaskGenerator gen = new TaskGenerator(config, 12345L);
        
        assertThrows(IllegalArgumentException.class,
            () -> gen.nextTask(0, false, () -> {}));
    }

    @Test
    @DisplayName("Should work with non-file mode (backward compatibility)")
    void testBackwardCompatibilityWithoutFile() {
        WorkloadEntry e1 = new WorkloadEntry("cpu1", WorkloadKind.CPU, 50L, 0.5);
        WorkloadEntry e2 = new WorkloadEntry("cpu2", WorkloadKind.CPU, 100L, 0.5);
        WorkloadConfig config = new WorkloadConfig(List.of(e1, e2));
        
        TaskGenerator gen = new TaskGenerator(config, 12345L);
        
        assertFalse(gen.isFileModeActive());
        assertEquals(0, gen.fileModeTaskCount());
        
        Task t1 = gen.nextTask(0, false, () -> {});
        assertNotNull(t1);
    }

    @Test
    @DisplayName("Should reject multiple file-based entries")
    void testRejectMultipleDurationFiles(@TempDir Path tempDir) throws Exception {
        Path dur1 = tempDir.resolve("dur1.txt");
        Path dur2 = tempDir.resolve("dur2.txt");
        Files.write(dur1, List.of("50"));
        Files.write(dur2, List.of("100"));
        
        WorkloadEntry e1 = new WorkloadEntry(
            "file1", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 0, dur1.toString());
        WorkloadEntry e2 = new WorkloadEntry(
            "file2", WorkloadKind.CPU, 0, 1.0, null, 0, 0, 0, dur2.toString());
        WorkloadConfig config = new WorkloadConfig(List.of(e1, e2));
        
        assertThrows(IllegalArgumentException.class,
            () -> new TaskGenerator(config, 12345L));
    }
}

