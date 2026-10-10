#!/bin/bash
# Quick accuracy test for CPU duration-controlled workload
# Tests 50µs, 100µs, 200µs, 300µs, 500µs targets

cat > /tmp/test_cpu_duration.yaml << 'EOF'
global:
  workerCount: 4
  maxInflight: 8
  seed: 12345
  warmupSeconds: 1
  measurementSeconds: 5
  taskCount: 10000
  retainCompletedTasks: false

workloads:
  cpu_test:
    - kind: CPU
      targetMicros: TARGETMICROS
      ratio: 1.0

runs:
  - name: cpu_TARGETMICROS_us_test
    mode: shared
    workload: cpu_test
    enabled: true
    pinning:
      enabled: false
EOF

cd /Users/wangs100/dev/multiqueue/TrailTest

echo "CPU Duration-Controlled Workload Accuracy Test"
echo "==============================================="
echo ""

for target in 50 100 200 300 500; do
    echo "Testing targetMicros=$target:"

    # Create temp config
    sed "s/TARGETMICROS/$target/g" /tmp/test_cpu_duration.yaml > /tmp/cpu_${target}us_test.yaml

    # Run benchmark and extract actual execution time
    output=$(mvn exec:java@bench \
        -Dexec.args="--config=/tmp/cpu_${target}us_test.yaml" \
        2>&1 | grep -i "execution" | tail -1)

    echo "  $output"
    echo ""
done

