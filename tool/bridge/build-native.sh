#!/usr/bin/env bash
# Minimal native build for the perf_event_open JNI bridge.
#
# Two modes - auto-detected from the script's neighbours:
#
#   1. STANDALONE  (this script + perf_bridge.c in the same dir)
#        ./build-native.sh
#      Output: ./libperfbridge.so  (right next to the script)
#
#   2. IN-REPO     (this script lives in <repo>/scripts/)
#        ./scripts/build-native.sh
#      Output: <repo>/native/linux-x86_64/libperfbridge.so
#
# Requirements:
#   - Linux (any arch)
#   - gcc
#   - JDK with <jni.h>  (autodetected via JAVA_HOME, then `java`)
#
# Environment overrides:
#   JAVA_HOME      preferred JDK
#   CC             compiler (default: gcc)
#   OUT            explicit output path
#   PERFBRIDGE_SRC explicit source path
#
# Exit codes: 0 ok, 1 missing source, 2 no <jni.h>, 3 gcc failed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---- source + output resolution (standalone vs in-repo) ----
if [[ -n "${PERFBRIDGE_SRC:-}" ]]; then
  SRC="${PERFBRIDGE_SRC}"
  DEFAULT_OUT="${SCRIPT_DIR}/libperfbridge.so"
elif [[ -f "${SCRIPT_DIR}/perf_bridge.c" ]]; then
  # Standalone: scp'd alongside perf_bridge.c.
  SRC="${SCRIPT_DIR}/perf_bridge.c"
  DEFAULT_OUT="${SCRIPT_DIR}/libperfbridge.so"
elif [[ -f "${SCRIPT_DIR}/../src/main/native/perf_bridge.c" ]]; then
  # In-repo: <repo>/scripts/build-native.sh.
  SRC="$(cd "${SCRIPT_DIR}/.." && pwd)/src/main/native/perf_bridge.c"
  DEFAULT_OUT="$(cd "${SCRIPT_DIR}/.." && pwd)/native/linux-x86_64/libperfbridge.so"
else
  echo "error: cannot find perf_bridge.c next to script or at ../src/main/native/." >&2
  echo "       set PERFBRIDGE_SRC=/path/to/perf_bridge.c to override." >&2
  exit 1
fi
OUT="${OUT:-$DEFAULT_OUT}"
CC="${CC:-gcc}"

if [[ ! -f "$SRC" ]]; then
  echo "error: source not found: $SRC" >&2
  exit 1
fi

# ---- detect <jni.h> ----
JNI_INC=""
if [[ -n "${JAVA_HOME:-}" && -d "${JAVA_HOME}/include" ]]; then
  JNI_INC="${JAVA_HOME}/include"
fi
if [[ -z "$JNI_INC" ]] && command -v java >/dev/null 2>&1; then
  JHOME="$(java -XshowSettings:properties -version 2>&1 \
            | awk -F'= ' '/java.home/ {gsub(/^[ \t]+|[ \t]+$/,"",$2); print $2; exit}')"
  if [[ -n "${JHOME:-}" && -d "${JHOME}/include" ]]; then
    JNI_INC="${JHOME}/include"
  fi
fi
# Last-ditch: common distro locations.
if [[ -z "$JNI_INC" ]]; then
  for c in /usr/lib/jvm/default-java/include \
           /usr/lib/jvm/java-21-openjdk-amd64/include \
           /usr/lib/jvm/java-17-openjdk-amd64/include \
           /usr/lib/jvm/java-11-openjdk-amd64/include; do
    if [[ -d "$c" ]]; then JNI_INC="$c"; break; fi
  done
fi
if [[ -z "$JNI_INC" ]]; then
  echo "error: cannot locate <jni.h>; set JAVA_HOME and retry." >&2
  echo "       e.g. JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac)))) $0" >&2
  exit 2
fi

# OS-specific subdir under include/ (linux, darwin, ...).
OS_INC=""
for d in linux darwin solaris freebsd; do
  if [[ -d "${JNI_INC}/${d}" ]]; then
    OS_INC="${JNI_INC}/${d}"
    break
  fi
done

mkdir -p "$(dirname "$OUT")"

echo "building libperfbridge.so"
echo "  src    : $SRC"
echo "  out    : $OUT"
echo "  cc     : $("$CC" --version 2>/dev/null | head -1 || echo "$CC")"
echo "  jni    : $JNI_INC ${OS_INC:+(+ $OS_INC)}"
echo "  uname  : $(uname -srm 2>/dev/null || true)"

if ! "$CC" -O2 -fPIC -shared \
        -I"$JNI_INC" ${OS_INC:+-I"$OS_INC"} \
        -o "$OUT" "$SRC"; then
  echo "error: $CC failed" >&2
  exit 3
fi

echo "ok    : $OUT"

# ---- post-build hints ----
if [[ -r /proc/sys/kernel/perf_event_paranoid ]]; then
  P="$(cat /proc/sys/kernel/perf_event_paranoid)"
  echo "perf  : /proc/sys/kernel/perf_event_paranoid = $P"
  if [[ "$P" -gt 2 ]]; then
    echo "        (>= 3 disables user perf_event_open; lower it or use CAP_PERFMON)" >&2
  fi
fi

cat <<EOF

next steps:
  1) start your JVM with  -Dperfbridge.library.path=$OUT, OR
  2) put $OUT on java.library.path, OR
  3) export LD_LIBRARY_PATH=$(dirname "$OUT"):\${LD_LIBRARY_PATH:-}
EOF
