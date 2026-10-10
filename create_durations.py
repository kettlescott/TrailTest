#!/usr/bin/env python3
import os
import re
from pathlib import Path

# Map of all baseline files and their durations
files_and_durations = {
    "baseline/shared/cpu/500000us": 500000,
    "baseline/shared/cpu/1800us": 1800,
    "baseline/shared/cpu/700us": 700,
    "baseline/shared/cpu/500us": 500,
    "baseline/shared/cpu/100us": 100,
    "baseline/shared/cpu/200us": 200,
    "baseline/shared/cpu/5000us": 5000,
    "baseline/shared/cpu/300us": 300,
    "baseline/shared/cpu/20000us": 20000,
    "baseline/shared/cpu/200000us": 200000,
    "baseline/shared/cpu/1600us": 1600,
    "baseline/shared/cpu/1400us": 1400,
    "baseline/shared/cpu/50us": 50,
    "baseline/shared/cpu/1000us": 1000,
    "baseline/shared/cpu/1200us": 1200,
    "baseline/shared/memory/500000us": 500000,
    "baseline/shared/memory/1800us": 1800,
    "baseline/shared/memory/700us": 700,
    "baseline/shared/memory/200us": 200,
    "baseline/shared/memory/5000us": 5000,
    "baseline/shared/memory/20000us": 20000,
    "baseline/shared/memory/300us": 300,
    "baseline/shared/memory/500us": 500,
    "baseline/sharded/cpu/500000us": 500000,
    "baseline/sharded/memory/500000us": 500000,
    "baseline/sharded/memory/300us": 300,
    "baseline/sharded/memory/1800us": 1800,
    "baseline/sharded/memory/100us": 100,
    "baseline/sharded/memory/500us": 500,
    "baseline/sharded/memory/700us": 700,
    "baseline/sharded/memory/20000us": 20000,
    "baseline/sharded/memory/200us": 200,
    "baseline/sharded/memory/200000us": 200000,
    "baseline/sharded/memory/5000us": 5000,
    "baseline/sharded/memory/1600us": 1600,
    "baseline/sharded/memory/1400us": 1400,
    "baseline/sharded/memory/1000us": 1000,
    "baseline/sharded/memory/1200us": 1200,
    "baseline/sharded/memory/50us": 50,
    "baseline/sharded/cpu/100us": 100,
    "baseline/sharded/cpu/1800us": 1800,
    "baseline/sharded/cpu/500us": 500,
    "baseline/sharded/cpu/700us": 700,
    "baseline/sharded/cpu/200us": 200,
    "baseline/sharded/cpu/5000us": 5000,
    "baseline/sharded/cpu/300us": 300,
    "baseline/sharded/cpu/20000us": 20000,
    "baseline/sharded/cpu/200000us": 200000,
    "baseline/sharded/cpu/1600us": 1600,
    "baseline/sharded/cpu/1400us": 1400,
    "baseline/sharded/cpu/50us": 50,
    "baseline/sharded/cpu/1000us": 1000,
    "baseline/sharded/cpu/1200us": 1200,
    "baseline/shared/memory/100us": 100,
    "baseline/shared/memory/200000us": 200000,
    "baseline/shared/memory/1600us": 1600,
    "baseline/shared/memory/1400us": 1400,
    "baseline/shared/memory/50us": 50,
    "baseline/shared/memory/1000us": 1000,
    "baseline/shared/memory/1200us": 1200,
}

base_dir = Path("/Users/wangs100/dev/multiqueue/TrailTest")

def create_durations_file(directory, duration_micros):
    """Create a durations.txt file with 100 lines of the same duration."""
    durations_file = directory / "durations.txt"
    with open(durations_file, 'w') as f:
        f.write(f"# Duration file for {duration_micros}µs tasks\n")
        f.write(f"# Each line is one task duration in microseconds\n")
        for _ in range(100):
            f.write(f"{duration_micros}\n")
    return durations_file

success_count = 0
for rel_path, duration in files_and_durations.items():
    dir_path = base_dir / rel_path
    if not dir_path.exists():
        print(f"Directory not found: {dir_path}")
        continue

    # Create durations.txt
    try:
        durations_file = create_durations_file(dir_path, duration)
        success_count += 1
        print(f"✓ Created: {durations_file.name}")
    except Exception as e:
        print(f"✗ Error creating durations.txt in {dir_path}: {e}")

print(f"\n{'='*60}")
print(f"Created {success_count}/{len(files_and_durations)} durations.txt files")
print(f"{'='*60}")

