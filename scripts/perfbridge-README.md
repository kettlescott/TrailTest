# perfbridge native build

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

A relative path or a directory is also accepted by
`-Dperfbridge.library.path` — the loader absolutises it and, if it
points at a directory, appends `libperfbridge.so` automatically.

The Java loader also looks for `./native/linux-x86_64/libperfbridge.so`
and `./libperfbridge.so` relative to the current working dir.

## Silence the JNI-restricted-methods warning (Java 24+)

On Java 24 and later you will see:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.scott.perf.PerfBridge ...
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning ...
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

This is informational — the library still loads. To silence it now
and future-proof the command (JEP 472 will turn this into an error
in a later release), add `--enable-native-access=ALL-UNNAMED`:

```bash
java --enable-native-access=ALL-UNNAMED \
     -Dperfbridge.library.path=/abs/path/to/libperfbridge.so \
     -jar TrailSystem-1.0-SNAPSHOT-all.jar ...
```

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
