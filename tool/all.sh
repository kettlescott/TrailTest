#!/bin/bash
set -euo pipefail

echo "========================================"
echo "Starting MEM 50us"
echo "========================================"
./run_m_50.sh

echo "MEM 50us finished. Cooling down 10s..."
sleep 10

echo "========================================"
echo "Starting MEM 100us"
echo "========================================"
./run_m_100.sh

echo "MEM 100us finished. Cooling down 10s..."
sleep 10

echo "========================================"
echo "Starting MEM 300us"
echo "========================================"
./run_m_300.sh

echo "========================================"
echo "All memory benchmarks completed."
echo "========================================"
