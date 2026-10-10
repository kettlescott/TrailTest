#!/usr/bin/env python3
"""
Bulk convert all 60 baseline files to durationFile mode
"""

import re
from pathlib import Path

# All 60 baseline files with their durations extracted from paths
baseline_conversions = [
    # Shared CPU
    ("baseline/shared/cpu/50us/benchmarks_shared_cpu_50us_baseline.yaml", 50),
    ("baseline/shared/cpu/100us/benchmarks_shared_cpu_100us_baseline.yaml", 100),
    ("baseline/shared/cpu/200us/benchmarks_shared_cpu_200us_baseline.yaml", 200),
    ("baseline/shared/cpu/300us/benchmarks_shared_cpu_300us_baseline.yaml", 300),
    ("baseline/shared/cpu/500us/benchmarks_shared_cpu_500us_baseline.yaml", 500),
    ("baseline/shared/cpu/700us/benchmarks_shared_cpu_700us_baseline.yaml", 700),
    ("baseline/shared/cpu/1000us/benchmarks_shared_cpu_1000us_baseline.yaml", 1000),
    ("baseline/shared/cpu/1200us/benchmarks_shared_cpu_1200us_baseline.yaml", 1200),
    ("baseline/shared/cpu/1400us/benchmarks_shared_cpu_1400us_baseline.yaml", 1400),
    ("baseline/shared/cpu/1600us/benchmarks_shared_cpu_1600us_baseline.yaml", 1600),
    ("baseline/shared/cpu/1800us/benchmarks_shared_cpu_1800us_baseline.yaml", 1800),
    ("baseline/shared/cpu/5000us/benchmarks_shared_cpu_5000us_baseline.yaml", 5000),
    ("baseline/shared/cpu/20000us/benchmarks_shared_cpu_20000us_baseline.yaml", 20000),
    ("baseline/shared/cpu/200000us/benchmarks_shared_cpu_200000us_baseline.yaml", 200000),
    ("baseline/shared/cpu/500000us/benchmarks_shared_cpu_500000us_baseline.yaml", 500000),

    # Shared Memory
    ("baseline/shared/memory/50us/benchmarks_shared_mem_50us_baseline.yaml", 50),
    ("baseline/shared/memory/100us/benchmarks_shared_mem_100us_baseline.yaml", 100),
    ("baseline/shared/memory/200us/benchmarks_shared_mem_200us_baseline.yaml", 200),
    ("baseline/shared/memory/300us/benchmarks_shared_mem_300us_baseline.yaml", 300),
    ("baseline/shared/memory/500us/benchmarks_shared_mem_500us_baseline.yaml", 500),
    ("baseline/shared/memory/700us/benchmarks_shared_mem_700us_baseline.yaml", 700),
    ("baseline/shared/memory/1000us/benchmarks_shared_mem_1000us_baseline.yaml", 1000),
    ("baseline/shared/memory/1200us/benchmarks_shared_mem_1200us_baseline.yaml", 1200),
    ("baseline/shared/memory/1400us/benchmarks_shared_mem_1400us_baseline.yaml", 1400),
    ("baseline/shared/memory/1600us/benchmarks_shared_mem_1600us_baseline.yaml", 1600),
    ("baseline/shared/memory/1800us/benchmarks_shared_mem_1800us_baseline.yaml", 1800),
    ("baseline/shared/memory/5000us/benchmarks_shared_mem_5000us_baseline.yaml", 5000),
    ("baseline/shared/memory/20000us/benchmarks_shared_mem_20000us_baseline.yaml", 20000),
    ("baseline/shared/memory/200000us/benchmarks_shared_mem_200000us_baseline.yaml", 200000),
    ("baseline/shared/memory/500000us/benchmarks_shared_mem_500000us_baseline.yaml", 500000),

    # Sharded CPU
    ("baseline/sharded/cpu/50us/benchmarks_sharded_cpu_50us_baseline.yaml", 50),
    ("baseline/sharded/cpu/100us/benchmarks_sharded_cpu_100us_baseline.yaml", 100),
    ("baseline/sharded/cpu/200us/benchmarks_sharded_cpu_200us_baseline.yaml", 200),
    ("baseline/sharded/cpu/300us/benchmarks_sharded_cpu_300us_baseline.yaml", 300),
    ("baseline/sharded/cpu/500us/benchmarks_sharded_cpu_500us_baseline.yaml", 500),
    ("baseline/sharded/cpu/700us/benchmarks_sharded_cpu_700us_baseline.yaml", 700),
    ("baseline/sharded/cpu/1000us/benchmarks_sharded_cpu_1000us_baseline.yaml", 1000),
    ("baseline/sharded/cpu/1200us/benchmarks_sharded_cpu_1200us_baseline.yaml", 1200),
    ("baseline/sharded/cpu/1400us/benchmarks_sharded_cpu_1400us_baseline.yaml", 1400),
    ("baseline/sharded/cpu/1600us/benchmarks_sharded_cpu_1600us_baseline.yaml", 1600),
    ("baseline/sharded/cpu/1800us/benchmarks_sharded_cpu_1800us_baseline.yaml", 1800),
    ("baseline/sharded/cpu/5000us/benchmarks_sharded_cpu_5000us_baseline.yaml", 5000),
    ("baseline/sharded/cpu/20000us/benchmarks_sharded_cpu_20000us_baseline.yaml", 20000),
    ("baseline/sharded/cpu/200000us/benchmarks_sharded_cpu_200000us_baseline.yaml", 200000),
    ("baseline/sharded/cpu/500000us/benchmarks_sharded_cpu_500000us_baseline.yaml", 500000),

    # Sharded Memory
    ("baseline/sharded/memory/50us/benchmarks_sharded_mem_50us_baseline.yaml", 50),
    ("baseline/sharded/memory/100us/benchmarks_sharded_mem_100us_baseline.yaml", 100),
    ("baseline/sharded/memory/200us/benchmarks_sharded_mem_200us_baseline.yaml", 200),
    ("baseline/sharded/memory/300us/benchmarks_sharded_mem_300us_baseline.yaml", 300),
    ("baseline/sharded/memory/500us/benchmarks_sharded_mem_500us_baseline.yaml", 500),
    ("baseline/sharded/memory/700us/benchmarks_sharded_mem_700us_baseline.yaml", 700),
    ("baseline/sharded/memory/1000us/benchmarks_sharded_mem_1000us_baseline.yaml", 1000),
    ("baseline/sharded/memory/1200us/benchmarks_sharded_mem_1200us_baseline.yaml", 1200),
    ("baseline/sharded/memory/1400us/benchmarks_sharded_mem_1400us_baseline.yaml", 1400),
    ("baseline/sharded/memory/1600us/benchmarks_sharded_mem_1600us_baseline.yaml", 1600),
    ("baseline/sharded/memory/1800us/benchmarks_sharded_mem_1800us_baseline.yaml", 1800),
    ("baseline/sharded/memory/5000us/benchmarks_sharded_mem_5000us_baseline.yaml", 5000),
    ("baseline/sharded/memory/20000us/benchmarks_sharded_mem_20000us_baseline.yaml", 20000),
    ("baseline/sharded/memory/200000us/benchmarks_sharded_mem_200000us_baseline.yaml", 200000),
    ("baseline/sharded/memory/500000us/benchmarks_sharded_mem_500000us_baseline.yaml", 500000),
]

base_dir = Path("/Users/wangs100/dev/multiqueue/TrailTest")
success = 0
failed = 0

for rel_path, duration_us in baseline_conversions:
    yaml_path = base_dir / rel_path

    if not yaml_path.exists():
        print(f"❌ File not found: {yaml_path}")
        failed += 1
        continue

    # Read original
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

    # Write converted content
    with open(yaml_path, 'w') as f:
        f.write(converted)

    # Create durations.txt
    durations_file = yaml_path.parent / 'durations.txt'
    if not durations_file.exists():
        with open(durations_file, 'w') as f:
            f.write(f"# Duration file for {duration_us}µs tasks\n")
            f.write(f"# Each line is one task duration in microseconds\n")
            for _ in range(100):
                f.write(f"{duration_us}\n")

    print(f"✓ {yaml_path.name} ({duration_us}µs)")
    success += 1

print(f"\n{'='*60}")
print(f"Conversion: {success} success, {failed} failed")
print(f"Total: {success + failed}")
print(f"{'='*60}")

if failed == 0:
    print("✅ All baseline files converted successfully!")

