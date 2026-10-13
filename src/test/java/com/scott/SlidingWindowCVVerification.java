package com.scott;

/**
 * Simple verification program to check SlidingWindowCV correctness.
 * 
 * Key verification points:
 * 1. Samples are executionNanos (pure execution time)
 * 2. Time units: all nanos internally, output in micros
 * 3. Constant samples (50µs) → CV = 0
 * 4. Welford variance accumulation is correct
 */
public class SlidingWindowCVVerification {

    public static void main(String[] args) {
        System.out.println("=== SlidingWindowCV Verification ===\n");
        
        // Test 1: Sample Integrity
        test1_SampleIntegrity();
        
        // Test 2: Time Unit Consistency
        test2_TimeUnitConsistency();
        
        // Test 3: Variance Accumulation
        test3_VarianceAccumulation();
        
        // Test 4: CRITICAL - Constant Samples → CV = 0
        test4_ConstantSamplesCV();
        
        // Test 5: Welford Validation
        test5_WelfordValidation();
        
        System.out.println("\n=== All Verification Tests Completed ===");
    }

    static void test1_SampleIntegrity() {
        System.out.println("Test 1: Sample Integrity (executionNanos only)");
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        window.addSample(now + 1_000_000, 50_000);      // 50µs
        window.addSample(now + 2_000_000, 75_000);      // 75µs
        window.addSample(now + 3_000_000, 25_000);      // 25µs
        
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long max = stats[2];
        
        System.out.printf("  Samples: 50, 75, 25 micros%n");
        System.out.printf("  Mean: %.3f µs (expected: 50.000)%n", mean / 1000.0);
        System.out.printf("  Max: %.3f µs (expected: 75.000)%n", max / 1000.0);
        
        assert mean == 50_000 : "Mean should be 50000ns";
        assert max == 75_000 : "Max should be 75000ns";
        System.out.println("  ✓ PASS\n");
    }

    static void test2_TimeUnitConsistency() {
        System.out.println("Test 2: Time Unit Consistency (nanos internally)");
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long targetMicros = 50;
        long targetNanos = targetMicros * 1000;
        
        long now = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            window.addSample(now + i * 1_000_000, targetNanos);
        }
        
        long[] stats = window.computeRawStatsNanos();
        long stddevNanos = stats[1];
        
        System.out.printf("  100 samples, all %.1f µs = %d nanos%n", 
            targetMicros, targetNanos);
        System.out.printf("  Stddev: %d nanos (expected: 0)%n", stddevNanos);
        
        assert stddevNanos == 0 : "Stddev should be 0 for identical samples";
        System.out.println("  ✓ PASS\n");
    }

    static void test3_VarianceAccumulation() {
        System.out.println("Test 3: Variance Accumulation (Welford)");
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        window.addSample(now, 10_000);          // 10µs
        window.addSample(now + 1_000_000, 20_000);    // 20µs
        window.addSample(now + 2_000_000, 30_000);    // 30µs
        
        // Manual: mean=20, variance = (100+0+100)/3 = 66.67
        // stddev ≈ 8.165
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long stddev = stats[1];
        
        System.out.printf("  Samples: 10, 20, 30 micros%n");
        System.out.printf("  Mean: %d nanos = %.1f µs (expected: 20000ns / 20µs)%n", 
            mean, mean / 1000.0);
        System.out.printf("  Stddev: %d nanos = %.3f µs (expected ~8.165µs)%n", 
            stddev, stddev / 1000.0);
        
        assert mean == 20_000 : "Mean should be 20000ns";
        assert stddev >= 8000 && stddev <= 8200 : 
            "Stddev should be around 8165ns, got " + stddev;
        System.out.println("  ✓ PASS\n");
    }

    static void test4_ConstantSamplesCV() {
        System.out.println("Test 4: CRITICAL - Constant Samples → CV = 0");
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // durationFile = 50µs for all tasks
        long targetMicros = 50;
        long targetNanos = targetMicros * 1000;  // 50000
        
        long now = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            window.addSample(now + i * 100_000, targetNanos);
        }
        
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long stddev = stats[1];
        
        // Verify fundamental property
        double cv = stddev / (double) mean;
        
        System.out.printf("  1000 samples, all exactly %d µs = %d nanos%n", 
            targetMicros, targetNanos);
        System.out.printf("  Mean: %d nanos (expected: %d)%n", mean, targetNanos);
        System.out.printf("  Stddev: %d nanos (expected: 0)%n", stddev);
        System.out.printf("  CV: %.6f (expected: 0.0)%n", cv);
        
        assert mean == targetNanos : 
            "Mean should be exactly " + targetNanos + "ns, got " + mean;
        assert stddev == 0 : 
            "Stddev should be exactly 0, got " + stddev;
        assert cv == 0.0 : 
            "CV should be exactly 0.0, got " + cv;
        System.out.println("  ✓ PASS - This is the FUNDAMENTAL verification!\n");
    }

    static void test5_WelfordValidation() {
        System.out.println("Test 5: Welford vs Raw Calculation");
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        long[] samples = { 10_000, 25_000, 15_000, 50_000, 30_000 };
        for (int i = 0; i < samples.length; i++) {
            window.addSample(now + i * 100_000, samples[i]);
        }
        
        double[] discrepancies = window.validateWelfordStats();
        
        System.out.printf("  5 samples: 10, 25, 15, 50, 30 micros%n");
        System.out.printf("  Mean discrepancy: %.2f ns (expected: ~0)%n", discrepancies[0]);
        System.out.printf("  Stddev discrepancy: %.2f ns (expected: ~0)%n", discrepancies[1]);
        
        assert Math.abs(discrepancies[0]) < 2.0 : 
            "Mean discrepancy too large: " + discrepancies[0];
        assert Math.abs(discrepancies[1]) < 2.0 : 
            "Stddev discrepancy too large: " + discrepancies[1];
        System.out.println("  ✓ PASS\n");
    }
}

