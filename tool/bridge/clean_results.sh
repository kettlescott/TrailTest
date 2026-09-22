#!/bin/bash

# Delete .data, .jfr, and .csv files recursively

set -e

echo "The following files will be deleted:"
find . -type f \( -name "*.data" -o -name "*.jfr" -o -name "*.csv" -o -name "*.log" \)

echo
read -p "Proceed with deletion? (y/N): " confirm

if [[ "$confirm" =~ ^[Yy]$ ]]; then
    find . -type f \( -name "*.data" -o -name "*.jfr" -o -name "*.csv" -o -name "*.log" \) -delete
    echo "Deletion completed."
else
    echo "Operation cancelled."
fi
