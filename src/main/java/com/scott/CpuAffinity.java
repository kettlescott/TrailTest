package com.scott;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Linux-specific CPU core pinning helper for benchmark worker threads.
 *
 * <p>Uses Java 21's <b>Foreign Function &amp; Memory API</b> (Project Panama /
 * FFM, JEP 442 — third preview in Java 21, finalized in Java 22) to call
 * {@code sched_setaffinity(2)} on the <em>current thread</em>
 * (pid&nbsp;=&nbsp;0 → calling thread).  This pins a single thread to one
 * logical CPU core without affecting any other thread or the JVM process
 * as a whole.
 *
 * <h3>Why FFM instead of JNA / JNI?</h3>
 * <ul>
 *   <li>No native shared library to compile or ship (no JNI).</li>
 *   <li>No third-party dependency (no JNA jar).</li>
 *   <li>The JDK provides everything — {@link Linker}, {@link SymbolLookup},
 *       {@link MemorySegment}, {@link Arena}.</li>
 *   <li>Better safety: arenas scope native memory lifetime; the JVM validates
 *       descriptors at link time.</li>
 * </ul>
 *
 * <h3>JVM launch options</h3>
 * <p>Because FFM is a <em>preview</em> feature in Java 21, the following
 * flags are required at <b>both compile time and runtime</b>:
 * <pre>
 *   # Compile:
 *   javac --enable-preview --source 21 ...
 *
 *   # Run:
 *   java --enable-preview --enable-native-access=ALL-UNNAMED ...
 * </pre>
 * <ul>
 *   <li>{@code --enable-preview} — unlocks the preview FFM API classes
 *       ({@code java.lang.foreign.*}).</li>
 *   <li>{@code --enable-native-access=ALL-UNNAMED} — permits calling
 *       native functions via {@link Linker#downcallHandle}.  Without it
 *       the JVM throws {@code IllegalCallerException} at runtime.
 *       If the code is in a named module, replace {@code ALL-UNNAMED}
 *       with the module name.</li>
 * </ul>
 * <p>In <b>Java 22+</b>, FFM is finalized (JEP 454) so
 * {@code --enable-preview} is no longer needed; only
 * {@code --enable-native-access} remains necessary.</p>
 *
 * <h3>Why per-thread pinning?</h3>
 * <p>Linux schedules threads independently.  {@code sched_setaffinity}
 * with pid&nbsp;0 modifies the <em>calling thread's</em> CPU affinity mask.
 * Other threads (including the JVM's GC, compiler, and main threads)
 * keep their default affinity.  This is exactly what we want for an A/B
 * benchmark: run A without pinning, run B with per-worker pinning,
 * and compare tail latency.</p>
 *
 * <h3>Platform support</h3>
 * <p>Only Linux is supported.  On other platforms {@link #pinCurrentThreadToCore}
 * throws {@link UnsupportedOperationException}.  The static method
 * {@link #isSupported()} can be checked before calling.</p>
 *
 * <h3>Verification</h3>
 * <p>After calling {@link #pinCurrentThreadToCore}, the pinning can be
 * verified from the shell:
 * <pre>
 *   # Find the thread's native TID:
 *   ps -T -p &lt;jvm_pid&gt;
 *
 *   # Check its affinity mask:
 *   taskset -p &lt;tid&gt;
 *
 *   # Or view the Cpus_allowed_list field:
 *   cat /proc/&lt;jvm_pid&gt;/task/&lt;tid&gt;/status | grep Cpus_allowed_list
 * </pre>
 * The mask should show only the assigned core.</p>
 */
public final class CpuAffinity {

    /**
     * Size of the cpu_set_t mask in bytes.  1024 bits = 128 bytes is the
     * default kernel maximum on most distros ({@code __CPU_SETSIZE / 8}).
     */
    private static final int CPU_SET_SIZE_BYTES = 128;

    private CpuAffinity() { }  // utility class

    /* ================================================================
     *  Lazy native handle initialization (inner-class holder idiom)
     *
     *  The NativeHolder class is only loaded when pinCurrentThreadToCore()
     *  or getCurrentAffinityMask() is first called.  This avoids loading
     *  libc or creating MethodHandles on platforms where pinning is never
     *  requested — and avoids a class-load failure on non-Linux.
     * ================================================================ */

    private static final class NativeHolder {

        /**
         * Downcall handle for:
         * {@code int sched_setaffinity(pid_t pid, size_t cpusetsize, const cpu_set_t *mask)}
         */
        static final MethodHandle SCHED_SET_AFFINITY;

        /**
         * Downcall handle for:
         * {@code int sched_getaffinity(pid_t pid, size_t cpusetsize, cpu_set_t *mask)}
         */
        static final MethodHandle SCHED_GET_AFFINITY;

        static {
            Linker linker = Linker.nativeLinker();

            // Load glibc explicitly.  sched_setaffinity is a Linux-specific
            // function that may not appear in the linker's defaultLookup()
            // (which only guarantees standard-C symbols).  Loading libc.so.6
            // is safe because it is always present on glibc-based Linux and
            // is already mapped into the JVM process.
            //
            // Arena.global() keeps the library loaded for the lifetime of
            // the JVM — exactly what we want for a utility singleton.
            SymbolLookup libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

            // Both sched_setaffinity and sched_getaffinity share the same
            // C signature on x86-64 Linux:
            //
            //   int func(pid_t pid,            → JAVA_INT   (4 bytes)
            //            size_t cpusetsize,     → JAVA_LONG  (8 bytes, unsigned long on LP64)
            //            cpu_set_t *mask)       → ADDRESS    (pointer)
            //
            FunctionDescriptor affinityDescriptor = FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,     // return type: int
                    ValueLayout.JAVA_INT,     // arg 1: pid_t  pid
                    ValueLayout.JAVA_LONG,    // arg 2: size_t cpusetsize
                    ValueLayout.ADDRESS       // arg 3: cpu_set_t *mask
            );

            SCHED_SET_AFFINITY = linker.downcallHandle(
                    libc.find("sched_setaffinity")
                        .orElseThrow(() -> new UnsatisfiedLinkError(
                                "sched_setaffinity not found in libc.so.6")),
                    affinityDescriptor
            );

            SCHED_GET_AFFINITY = linker.downcallHandle(
                    libc.find("sched_getaffinity")
                        .orElseThrow(() -> new UnsatisfiedLinkError(
                                "sched_getaffinity not found in libc.so.6")),
                    affinityDescriptor
            );
        }
    }

    /* ================================================================
     *  Public API
     * ================================================================ */

    /**
     * Returns {@code true} if CPU pinning is available on this platform.
     *
     * <p>Checks the OS name without loading any native library, so this
     * method is safe to call on any platform.
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /**
     * Pins the <em>current thread</em> to the specified logical CPU core.
     *
     * <p>This must be called from <em>inside</em> the worker thread's
     * {@code run()} method.  {@code sched_setaffinity} with pid&nbsp;0
     * targets the calling thread only — no other thread is affected.</p>
     *
     * <p><strong>Why inside {@code run()} and not from the thread creator?</strong>
     * {@code sched_setaffinity(0, ...)} always acts on the <em>calling</em>
     * kernel thread.  If we called it from the main thread, we would pin
     * the main thread itself, not the worker.  The Linux kernel identifies
     * threads by their TID, and {@code pid = 0} is a shorthand for
     * "the TID of whoever is executing this syscall right now".</p>
     *
     * <p><strong>FFM note:</strong> The {@link Arena#ofConfined() confined arena}
     * used for the cpu_set_t mask is scoped to this method call.  The native
     * memory is freed on exit — no leak possible.</p>
     *
     * @param coreId logical CPU core number (0-based, as shown by {@code lscpu})
     * @throws UnsupportedOperationException if the platform is not Linux
     * @throws IllegalArgumentException      if {@code coreId} is negative or
     *                                       exceeds the cpu_set_t capacity
     * @throws RuntimeException              if the syscall fails (e.g. invalid core,
     *                                       insufficient permissions)
     */
    public static void pinCurrentThreadToCore(int coreId) {
        if (!isSupported()) {
            throw new UnsupportedOperationException(
                    "CPU pinning is only supported on Linux. Current platform: "
                            + System.getProperty("os.name"));
        }
        if (coreId < 0) {
            throw new IllegalArgumentException("coreId must be >= 0, got: " + coreId);
        }

        int byteIndex = coreId / 8;
        int bitIndex  = coreId % 8;
        if (byteIndex >= CPU_SET_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "coreId " + coreId + " exceeds cpu_set_t capacity ("
                            + (CPU_SET_SIZE_BYTES * 8) + " cores max)");
        }

        // Allocate a cpu_set_t mask in off-heap memory via a confined arena.
        // The arena is closed at the end of the try block, freeing the
        // native memory deterministically — no GC involvement.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE_BYTES);
            mask.fill((byte) 0);                                        // CPU_ZERO
            mask.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (1 << bitIndex)); // CPU_SET

            // pid = 0 → current thread (this is per-thread, NOT per-process)
            int rc = (int) NativeHolder.SCHED_SET_AFFINITY.invokeExact(
                    0,                              // pid_t  pid
                    (long) CPU_SET_SIZE_BYTES,       // size_t cpusetsize
                    mask                             // cpu_set_t *mask
            );

            if (rc != 0) {
                throw new RuntimeException(
                        "sched_setaffinity failed for coreId " + coreId
                                + " (return code " + rc + "). "
                                + "Check that the core exists and the process has "
                                + "CAP_SYS_NICE or the core is in the cgroup cpuset.");
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            // MethodHandle.invokeExact declares 'throws Throwable'; wrap
            // any checked exception that is not ours.
            throw new RuntimeException("sched_setaffinity invocation failed", t);
        }
    }

    /**
     * Reads the current thread's CPU affinity mask and returns it as a
     * human-readable hex string (useful for verification / logging).
     *
     * @return hex string of the affinity mask, or a descriptive error
     */
    public static String getCurrentAffinityMask() {
        if (!isSupported()) {
            return "(not supported on " + System.getProperty("os.name") + ")";
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_SIZE_BYTES);
            mask.fill((byte) 0);

            int rc = (int) NativeHolder.SCHED_GET_AFFINITY.invokeExact(
                    0,                              // pid_t  pid  (current thread)
                    (long) CPU_SET_SIZE_BYTES,       // size_t cpusetsize
                    mask                             // cpu_set_t *mask  (output)
            );

            if (rc != 0) {
                return "(sched_getaffinity failed, rc=" + rc + ")";
            }

            // Find the highest non-zero byte to produce a compact hex string.
            int hi = CPU_SET_SIZE_BYTES - 1;
            while (hi > 0 && mask.get(ValueLayout.JAVA_BYTE, hi) == 0) {
                hi--;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = hi; i >= 0; i--) {
                sb.append(String.format("%02x", mask.get(ValueLayout.JAVA_BYTE, i)));
            }
            return sb.toString();
        } catch (RuntimeException | Error e) {
            return "(sched_getaffinity threw: " + e.getMessage() + ")";
        } catch (Throwable t) {
            return "(sched_getaffinity invocation failed: " + t.getMessage() + ")";
        }
    }
}
