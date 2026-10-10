# DurationFile Support Implementation

## Overview

Added optional `durationFile` support to workload configuration, enabling per-task duration specification from an external file.

## Two Mutually Exclusive Modes

### 1. Existing Mode (Unchanged)
- Uses `targetMicros` with existing `ratio` behavior
- Multiple workload entries can be mixed with ratios summing to 1.0
- Backward compatible with all existing configurations

### 2. File Mode (NEW)
- Uses `durationFile` path to read task durations
- No `targetMicros` or `ratio` needed (ratio must be 1.0)
- Only one workload entry may use `durationFile`
- Cannot mix with targetMicros in the same entry

## Configuration

### File Format
- One task duration per line (in microseconds)
- Skip empty lines and lines starting with `#` (comments)
- Example:
```
# CPU tasks with varying durations
50
100
200
150
75
```

### YAML Example
```yaml
workloads:
  cpu_file:
    - kind: CPU
      durationFile: durations.txt
      ratio: 1.0

runs:
  - name: my_run
    mode: shared
    workload: cpu_file
```

## Implementation Details

### New Classes
- `DurationFileTaskSource`: Reads and serves durations from file
  - `nextDurationMicros()`: Get next duration, -1 on EOF
  - `peekNextDurationMicros()`: Check next duration without consuming
  - Synchronized for thread-safety

- `FileCpuBoundWorkload`: CPU workload with file-based duration
  - Delegates to `CpuBoundWorkloadDuration` with read duration

- `FileMemoryBoundWorkload`: MEMORY workload with file-based duration
  - Delegates to `MemoryBoundWorkloadDuration` with read duration

### Modified Files
- `WorkloadEntry.java`:
  - Added `durationFile` field
  - Added `usesDurationFile()` helper
  - Enhanced YAML parsing to handle durationFile

- `TaskGenerator.java`:
  - Load and manage `DurationFileTaskSource`
  - Check for file-based entries at construction
  - Return `null` from `nextTask()` at EOF
  - Route file-mode tasks through `FileCpuBoundWorkload`/`FileMemoryBoundWorkload`

- `BenchmarkMain.java`:
  - Use file task count as `taskLimit` when file mode active
  - Handle EOF by releasing permit and breaking submission loop

- `BenchmarkConfigLoader.java`:
  - Fixed `normalizeRatios()` to pass `durationFile` parameter

## Validation Rules

1. **Mutually Exclusive**:
   - Cannot set both `durationFile` and `targetMicros`
   - Cannot set both `durationFile` and `targetMillis`
   - Cannot set both `durationFile` and `cpuIterations`
   - Cannot set both `durationFile` and `memorySteps`

2. **File Mode Only**:
   - `ratio` must be exactly 1.0
   - File must exist and contain at least one valid duration

3. **Single Entry Rule**:
   - Only one workload entry per config may use `durationFile`
   - Multiple entries can use `targetMicros`/`targetMillis` (legacy mode)

## Behavior

- **Task Execution**: Uses existing time-controlled logic (CPU/MEMORY duration-controlled)
- **maxInflight Respected**: Submission respects configured max in-flight limit
- **EOF Handling**: Returns null from `nextTask()` when file is exhausted
- **Backward Compatible**: All existing configurations work unchanged

## Testing

Comprehensive unit tests included:
- `DurationFileTaskSourceTest`: File reading and EOF handling
- `WorkloadEntryDurationFileModeTest`: Entry validation and parsing
- `FileCpuBoundWorkloadTest`: CPU workload execution from file
- `TaskGeneratorFileModeTest`: Task generation and EOF
- 20+ test cases covering validation, edge cases, and backward compatibility

