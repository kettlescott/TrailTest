#!/bin/zsh

base="/Users/wangs100/dev/multiqueue/TrailTest/baseline"

echo "Updating taskCount from 2000000 to 0 in shared/sharded..."
echo

find "$base/shared" "$base/sharded" -type f -name "*.yaml" ! -name "*.bak*" -exec perl -0pi -e 's/taskCount:\s*2000000/taskCount: 0/g' {} +

echo
echo "Done."