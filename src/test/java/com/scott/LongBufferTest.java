package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link LongBuffer}.
 * Covers normal operations, edge cases, and error conditions.
 */
@DisplayName("LongBuffer Tests")
class LongBufferTest {

    @Test
    @DisplayName("should create buffer with default capacity")
    void createsBufferWithDefaultCapacity() {
        LongBuffer buf = new LongBuffer();
        assertEquals(0, buf.size());
        assertTrue(buf.isEmpty());
    }

    @Test
    @DisplayName("should create buffer with custom capacity")
    void createsBufferWithCustomCapacity() {
        LongBuffer buf = new LongBuffer(100);
        assertEquals(0, buf.size());
        assertTrue(buf.isEmpty());
    }

    @Test
    @DisplayName("should reject non-positive initial capacity")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LongBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new LongBuffer(-1));
        assertThrows(IllegalArgumentException.class, () -> new LongBuffer(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("should add single value and update size")
    void addsSingleValue() {
        LongBuffer buf = new LongBuffer(10);
        buf.add(42L);
        assertEquals(1, buf.size());
        assertFalse(buf.isEmpty());
        assertEquals(42L, buf.get(0));
    }

    @Test
    @DisplayName("should add multiple values in sequence")
    void addsMultipleValues() {
        LongBuffer buf = new LongBuffer(10);
        for (int i = 0; i < 5; i++) {
            buf.add(i * 10L);
        }
        assertEquals(5, buf.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i * 10L, buf.get(i));
        }
    }

    @Test
    @DisplayName("should grow buffer when capacity exceeded")
    void growsBufferAutomatically() {
        LongBuffer buf = new LongBuffer(2);  // Tiny initial capacity
        for (int i = 0; i < 10; i++) {
            buf.add(100L + i);
        }
        assertEquals(10, buf.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(100L + i, buf.get(i));
        }
    }

    @Test
    @DisplayName("should handle large growth")
    void handlesLargeGrowth() {
        LongBuffer buf = new LongBuffer(1);
        int count = 10_000;
        for (int i = 0; i < count; i++) {
            buf.add(i);
        }
        assertEquals(count, buf.size());
        // Spot check some values
        assertEquals(0L, buf.get(0));
        assertEquals(5000L, buf.get(5000));
        assertEquals(count - 1, buf.get(count - 1));
    }

    @Test
    @DisplayName("should handle negative values")
    void handlesNegativeValues() {
        LongBuffer buf = new LongBuffer(5);
        buf.add(-1L);
        buf.add(-999999999999L);
        buf.add(Long.MIN_VALUE);
        assertEquals(3, buf.size());
        assertEquals(-1L, buf.get(0));
        assertEquals(-999999999999L, buf.get(1));
        assertEquals(Long.MIN_VALUE, buf.get(2));
    }

    @Test
    @DisplayName("should handle Long.MAX_VALUE and Long.MIN_VALUE")
    void handlesBoundaryValues() {
        LongBuffer buf = new LongBuffer(3);
        buf.add(Long.MIN_VALUE);
        buf.add(0L);
        buf.add(Long.MAX_VALUE);
        assertEquals(3, buf.size());
        assertEquals(Long.MIN_VALUE, buf.get(0));
        assertEquals(0L, buf.get(1));
        assertEquals(Long.MAX_VALUE, buf.get(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 9, 10})
    @DisplayName("should reject out-of-bounds access (get)")
    void rejectsOutOfBoundsGet(int index) {
        LongBuffer buf = new LongBuffer(10);
        for (int i = 0; i < 5; i++) buf.add(i);
        
        if (index >= 5 || index < 0) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.get(index));
        } else {
            assertDoesNotThrow(() -> buf.get(index));
        }
    }

    @Test
    @DisplayName("should throw on negative index")
    void rejectsNegativeIndex() {
        LongBuffer buf = new LongBuffer(10);
        buf.add(1L);
        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(-100));
    }

    @Test
    @DisplayName("should return copy via toArray()")
    void toArrayReturnsCopy() {
        LongBuffer buf = new LongBuffer(5);
        buf.add(10L);
        buf.add(20L);
        buf.add(30L);
        
        long[] arr = buf.toArray();
        assertEquals(3, arr.length);
        assertEquals(10L, arr[0]);
        assertEquals(20L, arr[1]);
        assertEquals(30L, arr[2]);
        
        // Modify returned array and verify buffer is unchanged
        arr[0] = 999L;
        assertEquals(10L, buf.get(0));
    }

    @Test
    @DisplayName("should return empty array when buffer is empty")
    void toArrayReturnsEmptyForEmptyBuffer() {
        LongBuffer buf = new LongBuffer(10);
        long[] arr = buf.toArray();
        assertEquals(0, arr.length);
    }

    @Test
    @DisplayName("should support sequential add and get operations")
    void supportsSequentialOperations() {
        LongBuffer buf = new LongBuffer(3);
        
        buf.add(100L);
        assertEquals(1, buf.size());
        assertEquals(100L, buf.get(0));
        
        buf.add(200L);
        assertEquals(2, buf.size());
        assertEquals(100L, buf.get(0));
        assertEquals(200L, buf.get(1));
        
        buf.add(300L);
        assertEquals(3, buf.size());
        assertEquals(100L, buf.get(0));
        assertEquals(200L, buf.get(1));
        assertEquals(300L, buf.get(2));
    }

    @Test
    @DisplayName("should handle stress test with many operations")
    void stressTestWithManyOperations() {
        LongBuffer buf = new LongBuffer(16);  // Small initial capacity
        int n = 100_000;
        
        for (int i = 0; i < n; i++) {
            buf.add(i * 2L);
        }
        
        assertEquals(n, buf.size());
        
        // Sample check throughout the buffer
        assertEquals(0L, buf.get(0));
        assertEquals(100_000L, buf.get(50_000));
        assertEquals((n - 1) * 2L, buf.get(n - 1));
    }

    @Test
    @DisplayName("should maintain size accurately through multiple grows")
    void maintainsSizeAccurateThroughGrows() {
        LongBuffer buf = new LongBuffer(1);
        int[] sizes = {1, 2, 4, 10, 20, 100};
        int cumulative = 0;
        
        for (int size : sizes) {
            for (int i = 0; i < size; i++) {
                buf.add(cumulative + i);
            }
            cumulative += size;
            assertEquals(cumulative, buf.size());
        }
    }

    @Test
    @DisplayName("should return correct size after all operations")
    void correctSizeAfterAllOperations() {
        LongBuffer buf = new LongBuffer(5);
        assertTrue(buf.isEmpty());
        assertEquals(0, buf.size());
        
        buf.add(1L);
        assertEquals(1, buf.size());
        assertFalse(buf.isEmpty());
        
        for (int i = 0; i < 99; i++) {
            buf.add(i);
        }
        assertEquals(100, buf.size());
    }

    @Test
    @DisplayName("should handle zero values")
    void handlesZeroValues() {
        LongBuffer buf = new LongBuffer(5);
        buf.add(0L);
        buf.add(0L);
        buf.add(0L);
        assertEquals(3, buf.size());
        assertEquals(0L, buf.get(0));
        assertEquals(0L, buf.get(1));
        assertEquals(0L, buf.get(2));
    }
}

