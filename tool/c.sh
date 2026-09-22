#!/bin/bash

set -euo pipefail

SCRIPTS=(
    "run_c_50.sh"
    "run_c_100.sh"
    "run_c_300.sh"
)

COOLDOWN=10

for script in "${SCRIPTS[@]}"; do
    echo "========================================"
    echo "Starting: $script"
    echo "Time: $(date)"
    echo "========================================"

    ./"$script"

    echo
    echo "Finished: $script"
    echo "Time: $(date)"

    if [[ "$script" != "${SCRIPTS[-1]}" ]]; then
        echo "Cooling down for ${COOLDOWN}s..."
        sleep "$COOLDOWN"
    fi
done

echo
echo "========================================"
echo "All CPU experiments completed."
echo "Time: $(date)"
echo "========================================"
