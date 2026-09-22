for f in benchmarks_shared_queue_sweep/cpu/50us/*.yaml; do
  echo "Running: $f"
  sudo ./switch_j.shell 25

  java \
    -Xms16g -Xmx16g \
    -XX:+UseZGC \
    --enable-preview \
    -Dperfbridge.library.path=bridge/libperfbridge.so \
    --enable-native-access=ALL-UNNAMED \
    -jar bridge/TrailSystem-1.0-SNAPSHOT-all.jar \
    --config="$f"

  echo "Finished: $f"
  echo "Cooling down for 10 seconds..."
  sleep 10
done
