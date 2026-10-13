import java.util.Arrays;

/**
 * Verification script for targetDeviation formula.
 * 
 * Formula: targetDeviation = sqrt(Σ(x_i - T)² / N) / T
 * where T = targetMicros (in nanos), x_i = individual execution time (in nanos)
 * 
 * Run with:
 *   javac verify_targetdeviation_formula.java
 *   java verify_targetdeviation_formula
 */
public class verify_targetdeviation_formula {

    static class TestCase {
        String name;
        long[] samplesNanos;  // execution times in nanos
        long targetNanos;     // target in nanos
        double expectedTargetDeviation;
        double expectedCV;    // for comparison
        
        TestCase(String name, long[] samplesNanos, long targetNanos, 
                 double expectedTargetDeviation, double expectedCV) {
            this.name = name;
            this.samplesNanos = samplesNanos;
            this.targetNanos = targetNanos;
            this.expectedTargetDeviation = expectedTargetDeviation;
            this.expectedCV = expectedCV;
        }
    }

    static double computeTargetDeviation(long[] samples, long targetNanos) {
        if (samples.length < 1 || targetNanos <= 0) {
            return Double.NaN;
        }
        
        double sumSquaredDiffs = 0.0;
        for (long x : samples) {
            double diff = x - targetNanos;
            sumSquaredDiffs += diff * diff;
        }
        
        double variance = sumSquaredDiffs / samples.length;
        double stddev = Math.sqrt(variance);
        double result = stddev / targetNanos;
        return result;
    }

    static double computeCV(long[] samples) {
        if (samples.length < 2) {
            return Double.NaN;
        }
        
        // Welford's algorithm
        double mean = 0.0;
        double M2 = 0.0;
        
        for (long x : samples) {
            double dx = (double) x;
            double delta = dx - mean;
            mean = mean + delta / (double)(Arrays.stream(samples)
                .takeWhile(s -> s == x).count());
        }
        
        // Simpler: direct mean calculation
        mean = Arrays.stream(samples).average().orElse(0.0);
        
        for (long x : samples) {
            double diff = x - mean;
            M2 += diff * diff;
        }
        
        double variance = M2 / samples.length;
        double stddev = Math.sqrt(variance);
        return stddev / mean;
    }

    static void runTest(TestCase tc) {
        double actualTargetDev = computeTargetDeviation(tc.samplesNanos, tc.targetNanos);
        double actualCV = computeCV(tc.samplesNanos);
        
        boolean targetDevOk = Math.abs(actualTargetDev - tc.expectedTargetDeviation) < 0.01;
        boolean cvOk = Math.abs(actualCV - tc.expectedCV) < 0.01;
        
        System.out.printf("\n[TEST] %s%n", tc.name);
        System.out.printf("  Samples: %s%n", Arrays.toString(tc.samplesNanos));
        System.out.printf("  Target:  %d ns%n", tc.targetNanos);
        System.out.printf("%n");
        System.out.printf("  targetDeviation: expected=%.4f, actual=%.4f %s%n", 
                tc.expectedTargetDeviation, actualTargetDev, targetDevOk ? "✓" : "✗");
        System.out.printf("  CV:              expected=%.4f, actual=%.4f %s%n", 
                tc.expectedCV, actualCV, cvOk ? "✓" : "✗");
        
        if (!targetDevOk || !cvOk) {
            System.out.printf("  FAILED%n");
        }
    }

    public static void main(String[] args) {
        System.out.println("===== targetDeviation Formula Verification =====\n");
        
        // Test case 1: All 50µs, T=50µs
        TestCase tc1 = new TestCase(
            "Test 1: All 50µs, T=50µs (perfect target)",
            new long[]{50000, 50000, 50000, 50000, 50000},  // all 50µs in nanos
            50000,  // T=50µs in nanos
            0.0,    // expected targetDev = 0
            0.0     // expected CV = 0
        );
        runTest(tc1);
        
        // Test case 2: All 53.5µs, T=50µs
        TestCase tc2 = new TestCase(
            "Test 2: All 53.5µs, T=50µs (uniform offset)",
            new long[]{53500, 53500, 53500, 53500, 53500},  // all 53.5µs in nanos
            50000,  // T=50µs in nanos
            0.07,   // expected targetDev ≈ 0.07 (3500/50000)
            0.0     // expected CV = 0 (no variation, so mean matches perfectly)
        );
        runTest(tc2);
        
        // Test case 3: Mixed [48, 50, 51, 52, 49, 505], T=50
        TestCase tc3 = new TestCase(
            "Test 3: Mixed [48, 50, 51, 52, 49, 505]µs, T=50µs",
            new long[]{48000, 50000, 51000, 52000, 49000, 505000},  // in nanos
            50000,  // T=50µs in nanos
            3.71,   // expected targetDev ≈ 3.71 (sqrt(207035/6)/50000 ≈ 185.77/50000)
            1.35    // expected CV ≈ 1.35 (180/125.8)
        );
        runTest(tc3);
        
        System.out.println("\n===== Verification Complete =====\n");
    }
}

