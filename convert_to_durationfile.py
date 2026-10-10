#!/usr/bin/env python3
"""
Convert baseline YAML files to use durationFile mode.

This script:
1. Finds all baseline YAML files
2. Converts workloads from targetMicros mode to durationFile mode
3. Creates durations.txt files in each directory with the task durations
4. Updates the YAML to reference durationFile instead of targetMicros

Usage:
    python3 convert_to_durationfile.py
    python3 convert_to_durationfile.py --dry-run
    python3 convert_to_durationfile.py --no-backup
"""

import os
import re
import sys
import yaml
from pathlib import Path
from typing import Optional, Dict, Any

def extract_duration_from_yaml(yaml_content: str) -> Optional[int]:
    """Extract targetMicros value from YAML content."""
    match = re.search(r'targetMicros:\s*(\d+)', yaml_content)
    if match:
        return int(match.group(1))
    return None

def extract_workload_name(yaml_content: str) -> Optional[str]:
    """Extract workload name from YAML."""
    # Look for 'workloads:' followed by the name
    match = re.search(r'workloads:\s+(\w+):', yaml_content)
    if match:
        return match.group(1)
    return None

def convert_yaml_to_durationfile(yaml_content: str, duration_micros: int) -> str:
    """
    Convert YAML from targetMicros mode to durationFile mode.

    Replaces:
        targetMicros: N
        ratio: 1.0

    With:
        durationFile: durations.txt
        ratio: 1.0
    """
    # Replace targetMicros: N with durationFile: durations.txt
    converted = re.sub(
        r'targetMicros:\s*\d+',
        'durationFile: durations.txt',
        yaml_content
    )
    return converted

def create_duration_file(directory: Path, duration_micros: int, count: int = 100) -> Path:
    """
    Create a durations.txt file with the specified duration repeated.

    Args:
        directory: Directory to create the file in
        duration_micros: Duration in microseconds per task
        count: Number of tasks to generate

    Returns:
        Path to the created file
    """
    duration_file = directory / "durations.txt"

    # Create duration file with 100 lines of the same duration
    with open(duration_file, 'w') as f:
        f.write(f"# Duration file for {duration_micros}µs tasks\n")
        f.write(f"# Each line is one task duration in microseconds\n")
        for _ in range(count):
            f.write(f"{duration_micros}\n")

    return duration_file

def process_baseline_file(yaml_path: Path, dry_run: bool = False, no_backup: bool = False) -> bool:
    """
    Process a single baseline YAML file.

    Returns True if successful, False otherwise.
    """
    try:
        # Read the file
        with open(yaml_path, 'r') as f:
            content = f.read()

        # Extract duration
        duration_micros = extract_duration_from_yaml(content)
        if duration_micros is None:
            print(f"⊘ Skipping {yaml_path}: No targetMicros found")
            return False

        workload_name = extract_workload_name(content)
        print(f"✓ Processing: {yaml_path}")
        print(f"  Duration: {duration_micros}µs, Workload: {workload_name}")

        if dry_run:
            print(f"  [DRY RUN] Would create: {yaml_path.parent}/durations.txt")
            print(f"  [DRY RUN] Would modify: {yaml_path}")
            return True

        # Create backup
        backup_path = yaml_path.with_suffix('.yaml.bak')
        if not no_backup and not backup_path.exists():
            with open(yaml_path, 'r') as f:
                backup_content = f.read()
            with open(backup_path, 'w') as f:
                f.write(backup_content)
            print(f"  Created backup: {backup_path}")

        # Convert YAML
        converted_content = convert_yaml_to_durationfile(content, duration_micros)

        # Write converted YAML
        with open(yaml_path, 'w') as f:
            f.write(converted_content)
        print(f"  Updated: {yaml_path}")

        # Create durations.txt
        duration_file = create_duration_file(yaml_path.parent, duration_micros, count=100)
        print(f"  Created: {duration_file}")

        return True

    except Exception as e:
        print(f"✗ Error processing {yaml_path}: {e}")
        return False

def main():
    """Main entry point."""
    dry_run = "--dry-run" in sys.argv
    no_backup = "--no-backup" in sys.argv

    if dry_run:
        print("🔍 DRY RUN MODE - No files will be modified\n")
    else:
        print("⚙️  Converting baseline files to durationFile mode\n")

    # Find all baseline YAML files
    baseline_dir = Path("/Users/wangs100/dev/multiqueue/TrailTest/baseline")
    yaml_files = list(baseline_dir.rglob("*_baseline.yaml"))

    if not yaml_files:
        print("✗ No baseline YAML files found")
        return 1

    print(f"Found {len(yaml_files)} baseline YAML files\n")

    success_count = 0
    for yaml_path in sorted(yaml_files):
        if process_baseline_file(yaml_path, dry_run, no_backup):
            success_count += 1
        print()

    print("=" * 80)
    print(f"Conversion complete: {success_count}/{len(yaml_files)} files processed")
    print("=" * 80)

    if dry_run:
        print("\n💡 Run without --dry-run to actually convert files:")
        print(f"   python3 {sys.argv[0]}")

    return 0 if success_count == len(yaml_files) else 1

if __name__ == "__main__":
    sys.exit(main())

