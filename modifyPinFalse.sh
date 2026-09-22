cd /Users/wangs100/dev/multiqueue/TrailTest

# 一键修改所有 CPU YAML
for f in benchmarks_shared_queue_sweep/cpu/*/*.yaml; do
  sed -i '' 's/    enabled: false$/    enabled: true/' "$f"
  sed -i '' 's/      enabled: true$/      enabled: false/' "$f"
done

# 一键修改所有 Memory YAML
for f in benchmarks_shared_queue_sweep/memory/*/*.yaml; do
  sed -i '' 's/    enabled: false$/    enabled: true/' "$f"
  sed -i '' 's/      enabled: true$/      enabled: false/' "$f"
done

echo "Done!"


#!/bin/bash

cd /Users/wangs100/dev/multiqueue/TrailTest

# 修改所有 CPU 和 Memory YAML 文件
for f in benchmarks_shared_queue_sweep/*/*/*.yaml; do
  sed -i '' '/asyncProfiler:/,/extraArgs:/ s/enabled: true$/enabled: false/' "$f"
done

echo "✓ Updated all YAML files: asyncProfiler.enabled = false"
