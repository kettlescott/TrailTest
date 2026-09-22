#!/bin/bash

if [ $# -ne 1 ]; then
    echo "Usage: $0 <perf.data>"
    exit 1
fi

PERF_DATA="$1"

if [ ! -f "$PERF_DATA" ]; then
    echo "Error: file not found: $PERF_DATA"
    exit 1
fi

BASE=$(basename "$PERF_DATA" .perf.data)
OUTPUT="${BASE}_perf_counts.txt"

perf report -i "$PERF_DATA" --stdio 2>&1 \
| awk '
/# Samples:/ {
    event=$0
    sub(/^.*event '\''/, "", event)
    sub(/'\''.*/, "", event)

    if (event == "cycles" ||
        event == "instructions" ||
        event == "cache-references" ||
        event == "cache-misses" ||
        event == "LLC-loads" ||
        event == "LLC-load-misses" ||
        event == "context-switches" ||
        event == "cpu-migrations") {
        current=event
    } else {
        current=""
    }
}

/# Event count \(approx\.\):/ && current != "" {
    count=$0
    sub(/^.*: /, "", count)

    printf "%-20s %s\n", current, count
}
' | tee "$OUTPUT"

echo
echo "Saved to: $OUTPUT"
