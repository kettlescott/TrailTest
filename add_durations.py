#!/usr/bin/env python3
"""
为 baseline shared/sharded 增加 1400、1600、1800 µs CPU 配置
基于现有的 1200us 配置文件复制并修改
"""

import os
import shutil
from pathlib import Path
import yaml

BASE_PATH = Path('/Users/wangs100/dev/multiqueue/TrailTest/baseline')
NEW_DURATIONS = [1400, 1600, 1800]
MODES = ['shared', 'sharded']
SOURCE_DURATION = 1200

def create_configs():
    """Create new configuration files for each duration"""

    created = []

    for mode in MODES:
        print(f"\n{'='*50}")
        print(f"Processing {mode.upper()}")
        print(f"{'='*50}")

        # Find source template (1200us)
        source_dir = BASE_PATH / mode / 'cpu' / f'{SOURCE_DURATION}us'
        source_file = source_dir / f'benchmarks_{mode}_cpu_{SOURCE_DURATION}us_baseline.yaml'

        if not source_file.exists():
            print(f"❌ Source not found: {source_file}")
            continue

        print(f"✓ Found source: {source_file.name}")

        # Read template
        with open(source_file, 'r') as f:
            config = yaml.safe_load(f)

        # Create config for each new duration
        for duration in NEW_DURATIONS:
            target_dir = BASE_PATH / mode / 'cpu' / f'{duration}us'
            target_file = target_dir / f'benchmarks_{mode}_cpu_{duration}us_baseline.yaml'

            # Create directory if not exists
            target_dir.mkdir(parents=True, exist_ok=True)

            # Update config
            new_config = config.copy()

            # Update workload definition
            workload_key = f'cpu_{duration}us'
            new_config['workloads'] = {workload_key: config['workloads'][f'cpu_{SOURCE_DURATION}us']}
            new_config['workloads'][workload_key][0]['targetMicros'] = duration

            # Update runs
            new_config['runs'] = [{
                'name': f'{mode}_cpu_{duration}us_baseline',
                'mode': mode,
                'workload': workload_key,
                'enabled': True,
                'pinning': {'enabled': False}
            }]

            # Write new config
            with open(target_file, 'w') as f:
                yaml.dump(new_config, f, default_flow_style=False, sort_keys=False)

            print(f"  ✅ Created: {target_file.relative_to(BASE_PATH)}")
            created.append(target_file)

    print(f"\n{'='*50}")
    print(f"✅ Successfully created {len(created)} files")
    print(f"{'='*50}\n")

    return created

if __name__ == '__main__':
    create_configs()
