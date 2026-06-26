#!/usr/bin/env python3
"""One-shot helper: write build-native.sh + tarball it."""
import base64, os, sys, tarfile, io
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "scripts", "build-native.sh")
SRC_C  = os.path.join(ROOT, "src", "main", "native", "perf_bridge.c")
README_PATH = os.path.join(ROOT, "scripts", "perfbridge-README.md")
TARBALL = os.path.join(ROOT, "perfbridge-native.tar.gz")

BUILD_SH = r'''#!/usr/bin/env bash
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
'''

README = '''# perfbridge native build

Two files, one Linux box, two commands.

## What this is

`libperfbridge.so` is the JNI shared library that the Java code in
`com.scott.perf.PerfBridge` loads at runtime to talk to
`perf_event_open(2)`. Without this `.so`, attribution still works but
all perf counter deltas are reported as 0 and `perfAvailable=false`.

## Build

```bash
# 1. scp the two files (this README is optional)
scp perf_bridge.c build-native.sh user@linux-host:~/perfbridge/

# 2. on the Linux host
cd ~/perfbridge
chmod +x build-native.sh
./build-native.sh
# => ./libperfbridge.so
```

The script auto-detects standalone mode (perf_bridge.c next to it)
and emits `libperfbridge.so` in the same directory.

## Requirements

- Linux
- `gcc`
- JDK with `<jni.h>` (any reasonably recent OpenJDK is fine).
  If `JAVA_HOME` is set the script uses it; otherwise it asks the
  running `java` for `java.home`, and finally falls back to common
  distro paths (`/usr/lib/jvm/...`).
- `/proc/sys/kernel/perf_event_paranoid <= 2` (or `CAP_PERFMON`).
  The build script prints the current paranoid level and warns if
  it is too restrictive.

## Tell the JVM where the .so is

Pick one:

```bash
# explicit path (preferred for research runs):
java -Dperfbridge.library.path=/abs/path/to/libperfbridge.so ...

# on java.library.path:
java -Djava.library.path=/abs/path/to ...

# via the linker:
export LD_LIBRARY_PATH=/abs/path/to:$LD_LIBRARY_PATH
```

The Java loader also looks for `./native/linux-x86_64/libperfbridge.so`
and `./libperfbridge.so` relative to the current working dir.

## Troubleshooting

- `cannot locate <jni.h>` -> install `openjdk-XX-jdk` (not just `-jre`)
  or point `JAVA_HOME` at a JDK with `include/`.
- `perf_event_open: Permission denied` -> `sudo sysctl -w
  kernel.perf_event_paranoid=2` (or lower) on the host.
- `Operation not supported` on some counters -> the PMU does not
  expose that event; the recorder will mark the slot unavailable and
  continue with the rest.

## Re-package on the dev machine

If you change `perf_bridge.c`, regenerate the tarball with:

```bash
./scripts/package-native.py            # creates perfbridge-native.tar.gz
scp perfbridge-native.tar.gz linux:~/
ssh linux 'tar xzf perfbridge-native.tar.gz && cd perfbridge && ./build-native.sh'
```
'''

def main():
    # 1. (re)write build-native.sh
    with open(SCRIPT, "w") as f:
        f.write(BUILD_SH)
    os.chmod(SCRIPT, 0o755)
    print("wrote", SCRIPT)

    # 2. write README
    with open(README_PATH, "w") as f:
        f.write(README)
    print("wrote", README_PATH)

    # 3. build tarball with just the 3 files at the top level "perfbridge/"
    if not os.path.exists(SRC_C):
        print("warning: missing", SRC_C, "- skipping tarball")
        return
    with tarfile.open(TARBALL, "w:gz") as t:
        def add(path, arcname, mode=0o644):
            data = open(path, "rb").read()
            info = tarfile.TarInfo("perfbridge/" + arcname)
            info.size = len(data)
            info.mode = mode
            info.mtime = int(os.path.getmtime(path))
            t.addfile(info, io.BytesIO(data))
        add(SRC_C,       "perf_bridge.c")
        add(SCRIPT,      "build-native.sh", mode=0o755)
        add(README_PATH, "README.md")
    print("wrote", TARBALL)
    print()
    print("to deploy on the Linux box:")
    print("  scp", TARBALL, "user@linux-host:~/")
    print("  ssh user@linux-host 'tar xzf perfbridge-native.tar.gz && cd perfbridge && ./build-native.sh'")

if __name__ == "__main__":
    main()

