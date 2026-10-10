#!/usr/bin/env python3
"""
Execute baseline file conversion to durationFile mode.
"""

import os
import re
from pathlib import Path

# List of all baseline files to convert
baseline_files = [
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/500000us/benchmarks_shared_cpu_500000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/1800us/benchmarks_shared_cpu_1800us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/700us/benchmarks_shared_cpu_700us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/500us/benchmarks_shared_cpu_500us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/100us/benchmarks_shared_cpu_100us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/200us/benchmarks_shared_cpu_200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/5000us/benchmarks_shared_cpu_5000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/300us/benchmarks_shared_cpu_300us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/20000us/benchmarks_shared_cpu_20000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/200000us/benchmarks_shared_cpu_200000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/1600us/benchmarks_shared_cpu_1600us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/1400us/benchmarks_shared_cpu_1400us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/50us/benchmarks_shared_cpu_50us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/1000us/benchmarks_shared_cpu_1000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/cpu/1200us/benchmarks_shared_cpu_1200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/500000us/benchmarks_shared_mem_500000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/1800us/benchmarks_shared_mem_1800us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/700us/benchmarks_shared_mem_700us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/200us/benchmarks_shared_mem_200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/5000us/benchmarks_shared_mem_5000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/20000us/benchmarks_shared_mem_20000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/300us/benchmarks_shared_mem_300us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/500us/benchmarks_shared_mem_500us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/500000us/benchmarks_sharded_cpu_500000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/500000us/benchmarks_sharded_mem_500000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/300us/benchmarks_sharded_mem_300us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/1800us/benchmarks_sharded_mem_1800us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/100us/benchmarks_sharded_mem_100us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/500us/benchmarks_sharded_mem_500us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/700us/benchmarks_sharded_mem_700us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/20000us/benchmarks_sharded_mem_20000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/200us/benchmarks_sharded_mem_200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/200000us/benchmarks_sharded_mem_200000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/5000us/benchmarks_sharded_mem_5000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/1600us/benchmarks_sharded_mem_1600us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/1400us/benchmarks_sharded_mem_1400us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/1000us/benchmarks_sharded_mem_1000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/1200us/benchmarks_sharded_mem_1200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/memory/50us/benchmarks_sharded_mem_50us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/100us/benchmarks_sharded_cpu_100us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/1800us/benchmarks_sharded_cpu_1800us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/500us/benchmarks_sharded_cpu_500us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/700us/benchmarks_sharded_cpu_700us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/200us/benchmarks_sharded_cpu_200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/5000us/benchmarks_sharded_cpu_5000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/300us/benchmarks_sharded_cpu_300us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/20000us/benchmarks_sharded_cpu_20000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/200000us/benchmarks_sharded_cpu_200000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/1600us/benchmarks_sharded_cpu_1600us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/1400us/benchmarks_sharded_cpu_1400us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/50us/benchmarks_sharded_cpu_50us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/1000us/benchmarks_sharded_cpu_1000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/sharded/cpu/1200us/benchmarks_sharded_cpu_1200us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/100us/benchmarks_shared_mem_100us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/200000us/benchmarks_shared_mem_200000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/1600us/benchmarks_shared_mem_1600us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/1400us/benchmarks_shared_mem_1400us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/50us/benchmarks_shared_mem_50us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/1000us/benchmarks_shared_mem_1000us_baseline.yaml",
    "/Users/wangs100/dev/multiqueue/TrailTest/baseline/shared/memory/1200us/benchmarks_shared_mem_1200us_baseline.yaml",
]

def extract_duration(yaml_path):
    """Extract duration in microseconds from filename or YAML."""
    path_str = str(yaml_path)
    # Try to extract from path like "baseline/shared/cpu/50us/"
    match = re.search(r'/(\d+)us/', path_str)
    if match:
        return int(match.group(1))
    return None

def convert_file(yaml_path):
    """Convert a single YAML file to durationFile mode."""
    yaml_path = Path(yaml_path)

    # Extract duration
    duration_micros = extract_duration(yaml_path)
    if not duration_micros:
        print(f"❌ Could not extract duration from {yaml_path}")
        return False

    # Read YAML
    with open(yaml_path, 'r') as f:
        content = f.read()

    # Create backup
    backup_path = yaml_path.with_suffix('.yaml.bak')
    if not backup_path.exists():
        with open(backup_path, 'w') as f:
            f.write(content)

    # Replace targetMicros with durationFile
    converted = re.sub(
        r'targetMicros:\s*\d+',
        'durationFile: durations.txt',
        content
    )

    # Write converted YAML
    with open(yaml_path, 'w') as f:
        f.write(converted)

    # Create durations.txt in the same directory
    durations_file = yaml_path.parent / 'durations.txt'
    if not durations_file.exists():
        with open(durations_file, 'w') as f:
            f.write(f"# Duration file for {duration_micros}µs tasks\n")
            f.write(f"# Each line is one task duration in microseconds\n")
            for _ in range(100):
                f.write(f"{duration_micros}\n")

    print(f"✓ Converted {yaml_path.name} ({duration_micros}µs)")
    return True

def main():
    print("Converting baseline files to durationFile mode...\n")

    success_count = 0
    for yaml_file in baseline_files:
        if convert_file(yaml_file):
            success_count += 1

    print(f"\n{'='*60}")
    print(f"Conversion complete: {success_count}/{len(baseline_files)} files")
    print(f"{'='*60}")

if __name__ == '__main__':
    main()

