package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileCpuBoundWorkload Tests")
class FileCpuBoundWorkloadTest {

    @Test
    @DisplayName("Should execute with durations from file")
    void testExecuteWithFileDurations(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50", "100"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        FileCpuBoundWorkload w1 = new FileCpuBoundWorkload(source, 12345L, 8);
        FileCpuBoundWorkload w2 = new FileCpuBoundWorkload(source, 12346L, 8);
        
        long result1 = w1.execute();
        assertNotEquals(0, result1);
        
        long result2 = w2.execute();
        assertNotEquals(0, result2);
        
        FileCpuBoundWorkload w3 = new FileCpuBoundWorkload(source, 12347L, 8);
        assertThrows(IllegalStateException.class, w3::execute);
    }

    @Test
    @DisplayName("Should throw IllegalStateException at EOF")
    void testThrowsAtEof(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("single.txt");
        Files.write(durFile, List.of("50"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        FileCpuBoundWorkload w = new FileCpuBoundWorkload(source, 12345L, 8);
        
        w.execute();
        assertThrows(IllegalStateException.class, w::execute);
    }

    @Test
    @DisplayName("Should accept valid seed and batchSize")
    void testValidateParameters(@TempDir Path tempDir) throws Exception {
        assertDoesNotThrow(() -> {
            Path durFile = Files.createTempFile("dur", ".txt");
            try {
                Files.write(durFile, List.of("50"));
                DurationFileTaskSource source = new DurationFileTaskSource(durFile);
                FileCpuBoundWorkload w = new FileCpuBoundWorkload(source, 0L, 1);
                w.execute();
            } finally {
                Files.deleteIfExists(durFile);
            }
        });
    }
}

