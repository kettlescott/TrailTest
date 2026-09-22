# Code Changes Reference

## Summary
- **Files Created**: 4 (MemoryBoundWorkloadDuration.java, DurationControlledMemoryTest.java, 2 YAML configs)
- **Files Modified**: 4 (WorkloadEntry.java, TaskGenerator.java, BenchmarkConfigLoader.java, plus docs)
- **Net Lines Added**: ~450 (implementation + tests)
- **Breaking Changes**: None (fully backward compatible)

## Key Changes by File

### WorkloadEntry.java
```java
// BEFORE: 7 fields
public record WorkloadEntry(
    String name, WorkloadKind kind, long targetMillis, double ratio,
    MemoryWorkloadConfig memory, int cpuIterations, int memorySteps
)

// AFTER: 8 fields  
public record WorkloadEntry(
    String name, WorkloadKind kind, long targetMillis, double ratio,
    MemoryWorkloadConfig memory, int cpuIterations, int memorySteps,
    long targetMicros  // NEW
)
```

**Validation changes** (compact constructor):
```java
// BEFORE:
if (targetMillis <= 0 && cpuIterations <= 0 && memorySteps <= 0) {
    throw new IllegalArgumentException(...);
}

// AFTER:
if (targetMillis <= 0 && cpuIterations <= 0 && memorySteps <= 0 && targetMicros <= 0) {
    throw new IllegalArgumentException(...);
}
```

**Parser changes** (fromMap):
```java
// BEFORE: Parse targetMillis, then targetMicros
Object tm = em.get("targetMillis");
long targetMillis = ...;
Object tmicro = em.get("targetMicros");
long targetMicros = tmicro == null ? 0L : Long.parseLong(...);

// AFTER: Parse targetMicros first, make targetMillis optional when targetMicros present
Object tmicro = em.get("targetMicros");
long targetMicros = tmicro == null ? 0L : Long.parseLong(...);
Object tm = em.get("targetMillis");
long targetMillis;
if (tm != null) {
    targetMillis = Long.parseLong(String.valueOf(tm));
} else if (cpuIterations > 0 || memorySteps > 0 || targetMicros > 0) {  // NEW: targetMicros check
    targetMillis = 0L;
} else {
    throw new IllegalArgumentException(...);
}
```

### TaskGenerator.java

**Calibration record**:
```java
// BEFORE: 10 fields
public record Calibration(String name, WorkloadKind kind, long targetMillis,
    int cpuIterations, boolean fixedCpuIterations,
    int memorySteps, int memoryBufferMB,
    MemoryBoundWorkload.AccessPattern memoryAccessPattern, boolean memoryWriteBack,
    boolean fixedMemorySteps)

// AFTER: 12 fields
public record Calibration(..., boolean fixedMemorySteps,
    long targetMicros,       // NEW
    String memoryMode)       // NEW: "FIXED_STEP" or "DURATION_CONTROLLED"
```

**EntryState class**:
```java
// BEFORE: 5 memory-related fields
final int memorySteps;
final long[] memoryBuffer;
final MemoryBoundWorkload.AccessPattern memoryPattern;
final boolean memoryWriteBack;

// AFTER: 7 memory-related fields (same as above, plus):
final long memoryTargetMicros;              // NEW
final String memoryMode;                    // NEW
```

**EntryState.constructor** (MEMORY case):
```java
// BEFORE:
if (entry.memorySteps() > 0) {
    this.memorySteps = entry.memorySteps();
} else {
    this.memorySteps = WorkloadCalibrator.calibrateMemorySteps(...);
}

// AFTER: Mode precedence
if (entry.targetMicros() > 0) {
    this.memoryMode = "DURATION_CONTROLLED";
    this.memoryTargetMicros = entry.targetMicros();
    this.memorySteps = 0;
} else if (entry.memorySteps() > 0) {
    this.memoryMode = "FIXED_STEP";
    this.memorySteps = entry.memorySteps();
    this.memoryTargetMicros = 0L;
} else {
    this.memoryMode = "FIXED_STEP";
    this.memorySteps = WorkloadCalibrator.calibrateMemorySteps(...);
    this.memoryTargetMicros = 0L;
}
```

**createWorkload method**:
```java
// BEFORE:
case MEMORY -> new MemoryBoundWorkload(
    es.memoryBuffer, es.memorySteps, es.memoryPattern, taskSeed, es.memoryWriteBack);

// AFTER:
case MEMORY -> {
    if ("DURATION_CONTROLLED".equals(es.memoryMode)) {
        yield new MemoryBoundWorkloadDuration(
            es.memoryBuffer, es.memoryTargetMicros, es.memoryPattern,
            taskSeed, es.memoryWriteBack, 8);  // batchSize=8
    } else {
        yield new MemoryBoundWorkload(
            es.memoryBuffer, es.memorySteps, es.memoryPattern, taskSeed, es.memoryWriteBack);
    }
}
```

**calibrations() method**:
```java
// AFTER: Add new fields to Calibration output
out.add(new Calibration(
    ...,
    s.memorySteps,
    bufferMB,
    s.memoryPattern,
    s.memoryWriteBack,
    s.entry.usesFixedMemorySteps(),
    s.memoryTargetMicros,   // NEW
    s.memoryMode));         // NEW
```

### BenchmarkConfigLoader.java

**normalizeRatios method**:
```java
// BEFORE:
out.add(new WorkloadEntry(
    e.name(), e.kind(), e.targetMillis(),
    e.ratio() / sum, e.memory(), e.cpuIterations(), e.memorySteps()));

// AFTER:
out.add(new WorkloadEntry(
    e.name(), e.kind(), e.targetMillis(),
    e.ratio() / sum, e.memory(), e.cpuIterations(), e.memorySteps(),
    e.targetMicros()));  // NEW: preserve targetMicros
```

## New Files

### MemoryBoundWorkloadDuration.java (104 lines)
Core duration-controlled workload class.

Key methods:
- Constructor: `MemoryBoundWorkloadDuration(long[] buffer, long targetMicros, AccessPattern pattern, long seed, boolean writeBack, int batchSize)`
- `execute()`: Main workload loop
- `lastMemoryOperations()`: Diagnostic accessor

### DurationControlledMemoryTest.java (183 lines)
Comprehensive test suite for duration-controlled mode.

Test methods:
- `parsesDurationControlledMemoryEntry()`: YAML parsing
- `durationControlledMemoryInitializesCorrectly()`: TaskGenerator mode detection
- `fixedStepModePreservesOldBehavior()`: Backward compatibility
- `targetMicrosPrecedesMemorySteps()`: Mode precedence
- `durationControlledWorkloadExecutes()`: Actual execution timing

### benchmarks_shared_mem_duration_controlled.yaml (120 lines)
Full example with MEM50/MEM100/MEM300 shared-queue runs.

### benchmarks_mem_duration_test.yaml (65 lines)
Quick test configuration with 3 duration-controlled workloads.

## Testing Results

```
DurationControlledMemoryTest
  ✅ 5/5 tests passing
  
Compilation
  ✅ mvn clean compile successful
  
Backward Compatibility  
  ✅ All existing configs still parse
  ✅ Fixed-step mode unchanged
  ✅ Calibration mode unchanged
```

## Usage Example

### Before (Fixed Step)
```yaml
workloads:
  mem:
    - kind: MEMORY
      memorySteps: 320  # Task always does exactly 320 memory accesses
      ratio: 1.0
```
**Problem**: Execution time varies with queue contention, cache state, etc.

### After (Duration Controlled)
```yaml
workloads:
  mem:
    - kind: MEMORY
      targetMicros: 100  # Task executes for ~100 microseconds
      ratio: 1.0
```
**Solution**: Execution time is stable, predictable, queue-independent.

## Diagnostic Output

### Calibration Summary (existing + new fields)
```
calibration[0]=name=mem_50us, kind=MEMORY, targetMillis=0, targetMicros=50,
mode=DURATION_CONTROLLED, bufferMB=512, accessPattern=RANDOM, writeBack=false
```

### Latency Statistics (existing - unchanged)
```
executionMs.avg=0.050
executionMs.p50=0.049
executionMs.p95=0.055
executionMs.p99=0.062
```

Expected: avg ≈ 0.050, with small variance (±10 µs typical)

