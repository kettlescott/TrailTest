#!/bin/bash

set -u

BASE="/home/wangs100/private/results"

for data in "$BASE"/*/*.perf.data; do
    [ -f "$data" ] || continue

    dir=$(dirname "$data")
    base=$(basename "$data" .perf.data)
    output="$dir/${base}.perf.txt"

    echo "Processing: $data"
    echo "       -> : $output"

    perf report \
        -i "$data" \
        --stdio \
        --no-children \
        > "$output"

    if [ $? -eq 0 ]; then
        echo "OK: $output"
    else
        echo "FAILED: $data" >&2
    fi
done
