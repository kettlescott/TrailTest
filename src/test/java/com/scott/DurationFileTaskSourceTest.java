package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DurationFileTaskSource Tests")
class DurationFileTaskSourceTest {

    @Test
    @DisplayName("Should read valid durations from file")
    void testReadValidDurations(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("50", "100", "200"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        assertEquals(3, source.totalTasks());
        assertEquals(50L, source.nextDurationMicros());
        assertEquals(100L, source.nextDurationMicros());
        assertEquals(200L, source.nextDurationMicros());
        assertEquals(-1L, source.nextDurationMicros());
        assertEquals(-1L, source.nextDurationMicros());
    }

    @Test
    @DisplayName("Should skip comments and empty lines")
    void testSkipCommentsAndEmptyLines(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of(
            "# Header",
            "50",
            "  ",
            "100",
            "# Comment",
            "",
            "200"
        ));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        assertEquals(3, source.totalTasks());
        assertEquals(50L, source.nextDurationMicros());
        assertEquals(100L, source.nextDurationMicros());
        assertEquals(200L, source.nextDurationMicros());
    }

    @Test
    @DisplayName("Should reject non-existent file")
    void testRejectNonExistentFile() {
        assertThrows(IllegalArgumentException.class, 
            () -> new DurationFileTaskSource(Path.of("/nonexistent/path.txt")));
    }

    @Test
    @DisplayName("Should reject empty file")
    void testRejectEmptyFile(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("empty.txt");
        Files.write(durFile, List.of());
        
        assertThrows(IllegalArgumentException.class,
            () -> new DurationFileTaskSource(durFile));
    }

    @Test
    @DisplayName("Should reject file with only comments")
    void testRejectFileWithOnlyComments(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("comments_only.txt");
        Files.write(durFile, List.of("# comment 1", "# comment 2"));
        
        assertThrows(IllegalArgumentException.class,
            () -> new DurationFileTaskSource(durFile));
    }

    @Test
    @DisplayName("Should reject invalid duration values")
    void testRejectInvalidDuration(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("invalid.txt");
        Files.write(durFile, List.of("50", "notanumber", "100"));
        
        assertThrows(IllegalArgumentException.class,
            () -> new DurationFileTaskSource(durFile));
    }

    @Test
    @DisplayName("Should reject zero or negative durations")
    void testRejectNonPositiveDuration(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("negative.txt");
        Files.write(durFile, List.of("50", "0", "100"));
        
        assertThrows(IllegalArgumentException.class,
            () -> new DurationFileTaskSource(durFile));
    }

    @Test
    @DisplayName("Should track read position")
    void testTrackReadPosition(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("10", "20", "30"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        assertEquals(0, source.tasksRead());
        source.nextDurationMicros();
        assertEquals(1, source.tasksRead());
        source.nextDurationMicros();
        assertEquals(2, source.tasksRead());
    }

    @Test
    @DisplayName("Should support reset")
    void testReset(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("durations.txt");
        Files.write(durFile, List.of("10", "20"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        source.nextDurationMicros();
        source.nextDurationMicros();
        assertEquals(-1L, source.nextDurationMicros());
        
        source.reset();
        assertEquals(10L, source.nextDurationMicros());
    }

    @Test
    @DisplayName("Should handle large values")
    void testLargeValues(@TempDir Path tempDir) throws Exception {
        Path durFile = tempDir.resolve("large.txt");
        Files.write(durFile, List.of("1000000000", "2000000000"));
        
        DurationFileTaskSource source = new DurationFileTaskSource(durFile);
        assertEquals(1000000000L, source.nextDurationMicros());
        assertEquals(2000000000L, source.nextDurationMicros());
    }
}

