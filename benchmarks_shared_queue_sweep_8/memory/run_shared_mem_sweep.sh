#!/usr/bin/env bash
# Experiment 1 — shared-queue contention sweep (MEMORY-bound)
# Runs 6 configurations sequentially, workerCount=32 fixed.
#
# Usage:
#   ./run_shared_mem_sweep.sh                     # run all six (K=1,2,4,8,16,32)
#   ./run_shared_mem_sweep.sh 1 8 32              # run a subset
#
# Output artifacts (JFR / perf / summary) are written to the JVM's working
# directory as configured by the yaml's ${runName} substitution.

set -euo pipefail

# ---- paths (edit if your layout differs) --------------------------------
JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-25-openjdk/bin/java}"
JAR="${JAR:-test/TrailSystem-1.0-SNAPSHOT-all.jar}"
YAML_DIR="benchmarks_shared_queue_sweep/memory"
LOG_DIR="${LOG_DIR:-result/shared_mem_sweep}"
# ------------------------------------------------------------------------

mkdir -p "$LOG_DIR"

DEFAULT_KS=(1 2 4 8 16 32)
if [[ $# -gt 0 ]]; then
  KS=("$@")
else
  KS=("${DEFAULT_KS[@]}")
fi

echo "[sweep] jar=$JAR"
echo "[sweep] yaml_dir=$YAML_DIR"
echo "[sweep] log_dir=$LOG_DIR"
echo "[sweep] configs: K=${KS[*]}"
echo

for K in "${KS[@]}"; do
  YAML="${YAML_DIR}/benchmarks_shared_mem_4ms_q${K}.yaml"
  LOG="${LOG_DIR}/shared_mem_4ms_q${K}.log"

  if [[ ! -f "$YAML" ]]; then
    echo "[sweep] SKIP  K=$K  (missing $YAML)"
    continue
  fi

  echo "======================================================================"
  echo "[sweep] START K=$K  yaml=$YAML  log=$LOG   $(date -Iseconds)"
  echo "======================================================================"

  "$JAVA_BIN" \
    -Xms16g -Xmx16g \
    -XX:+UseZGC \
    --enable-preview \
    -jar "$JAR" \
    --config="$YAML" \
    2>&1 | tee "$LOG"

  echo "[sweep] DONE  K=$K   $(date -Iseconds)"
  echo
done

echo "[sweep] all runs finished."

