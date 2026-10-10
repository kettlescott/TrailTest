#!/usr/bin/env python3
"""Create all remaining durations.txt files"""

from pathlib import Path

base_dir = Path("/Users/wangs100/dev/multiqueue/TrailTest")

# All duration values in microseconds
durations_us = [50, 100, 200, 300, 500, 700, 1000, 1200, 1400, 1600, 1800, 5000, 20000, 200000, 500000]

# Categories to process
categories = [
    ("shared/cpu", 15),
    ("sharded/cpu", 15),
    ("sharded/memory", 15),
]

def create_durations_txt(directory, duration_us):
    """Create durations.txt file with 100 lines of the duration"""
    durations_file = directory / "durations.txt"
    if durations_file.exists():
        return False  # Already exists

    with open(durations_file, 'w') as f:
        f.write(f"# Duration file for {duration_us}µs tasks\n")
        f.write(f"# Each line is one task duration in microseconds\n")
        for _ in range(100):
            f.write(f"{duration_us}\n")

    return True

count = 0
for category, num_files in categories:
    for duration_us in durations_us:
        dir_path = base_dir / "baseline" / category / f"{duration_us}us"

        if not dir_path.exists():
            continue

        if create_durations_txt(dir_path, duration_us):
            print(f"✓ Created {category}/{duration_us}us/durations.txt")
            count += 1
        else:
            print(f"  Skipped {category}/{duration_us}us/durations.txt (already exists)")

print(f"\nTotal created: {count}")

