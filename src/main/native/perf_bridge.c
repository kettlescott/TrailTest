/*
 * perf_bridge.c — minimal Linux perf_event_open JNI bridge.
 *
 * -----------------------------------------------------------------
 *  Why raw (value, time_enabled, time_running) and NOT scaling in C
 * -----------------------------------------------------------------
 *
 * Each perf fd is opened with
 *
 *     read_format = PERF_FORMAT_TOTAL_TIME_ENABLED
 *                 | PERF_FORMAT_TOTAL_TIME_RUNNING
 *
 * so every read() returns three monotonically-increasing u64s
 * (counter value, total time the event has been enabled, total time
 * it has actually been running on the PMU). Under multiplexing
 * time_running < time_enabled.
 *
 * The standard "scaled" value reported by tools such as perf-stat is
 *
 *     scaled_total = value * time_enabled / time_running.
 *
 * For per-task sampled attribution we bracket workload.execute()
 * with a "before" read and an "after" read. The quantity we want is
 * the count incurred *during the bracket*. Two natural definitions:
 *
 *   (A)  delta = scale(after.value) - scale(before.value)
 *              = afterValue  * afterEnabled  / afterRunning
 *              - beforeValue * beforeEnabled / beforeRunning
 *
 *   (B)  delta = (after.value   - before.value)
 *              * (after.enabled - before.enabled)
 *              / (after.running - before.running)
 *
 * (A) is what you get if the C bridge scales each absolute read and
 * Java subtracts. It is WRONG under multiplexing because the
 * cumulative scaling factors (enabled/running) on either side of the
 * bracket carry the history of every previous schedule slice, not
 * just the slice that overlaps the bracket. The error grows with
 * total run time before the bracketed task.
 *
 * (B) scales the *delta* by the multiplexing fraction observed *over
 * the same bracket*. It correctly answers "if the counter had been
 * pinned for the entire bracket, what would the per-task count be?"
 * It is also exactly what perf-stat does for a single measurement
 * window with no prior history.
 *
 * Therefore this bridge returns raw triples and lets the Java side
 * (or an offline analyzer) compute delta-of-raws and apply the
 * scaling on the delta. The native code stays minimal: no math
 * beyond reading 24 bytes per fd, zero allocations on the hot path.
 *
 * -----------------------------------------------------------------
 *  Design notes
 * -----------------------------------------------------------------
 *
 *   - Each fd is opened standalone (no leader/follower group).
 *   - pid=0 (calling thread), cpu=-1 (counter follows the thread).
 *   - disabled=0  =>  counting starts immediately on open.
 *   - exclude_kernel=0 and exclude_hv=0 so we observe everything
 *     that touches the worker thread.
 *   - If a counter fails to open we return -1 for that fd; Java
 *     side marks the slot unavailable and continues.
 *   - The caller passes type[i] = -1 to skip a slot entirely (i.e.
 *     selective opening). That slot stays at fd = -1.
 *   - No allocations on hot paths. nativeReadAllRaw() is a tight
 *     loop of one read() syscall per fd.
 *
 * Build:
 *   gcc -O2 -fPIC -shared -o native/linux-x86_64/libperfbridge.so \
 *       src/main/native/perf_bridge.c
 */

#define _GNU_SOURCE
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

#ifdef __linux__
#  include <linux/perf_event.h>
#  include <sys/syscall.h>
#  include <sys/ioctl.h>
#  include <sched.h>
#endif

#ifdef __linux__
static long perf_event_open(struct perf_event_attr *hw_event, pid_t pid,
                            int cpu, int group_fd, unsigned long flags) {
    return syscall(__NR_perf_event_open, hw_event, pid, cpu, group_fd, flags);
}

struct scaled_read {
    uint64_t value;
    uint64_t time_enabled;
    uint64_t time_running;
};
#endif

/*
 * Java signature:
 *   private static native int[] nativeOpen(int[] types, long[] configs);
 *
 * For each (type[i], config[i]) opens a perf fd attached to the
 * calling thread. type[i] == -1 means "skip this slot". Entries
 * are -1 on failure (or non-Linux).
 */
JNIEXPORT jintArray JNICALL
Java_com_scott_perf_PerfBridge_nativeOpen(JNIEnv *env, jclass cls,
                                          jintArray jTypes,
                                          jlongArray jConfigs) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, jTypes);
    jsize n2 = (*env)->GetArrayLength(env, jConfigs);
    if (n2 < n) n = n2;

    jintArray out = (*env)->NewIntArray(env, n);
    if (out == NULL) return NULL;

    jint *fds = (jint *) calloc((size_t) n, sizeof(jint));
    if (fds == NULL) {
        /* Defensive: even when our scratch allocation fails the caller
         * gets back a well-formed int[] of length n, every slot set to
         * -1 ("counter unavailable"). The Java side then treats this
         * exactly like a normal "every fd failed to open" outcome
         * instead of having to handle a NULL return. We fill the array
         * via SetIntArrayRegion using a tiny stack chunk so we never
         * heap-allocate on this failure path. */
        jint minus_ones[32];
        for (jsize k = 0; k < 32; k++) minus_ones[k] = -1;
        jsize off = 0;
        while (off < n) {
            jsize step = (n - off) > 32 ? 32 : (n - off);
            (*env)->SetIntArrayRegion(env, out, off, step, minus_ones);
            off += step;
        }
        return out;
    }
    for (jsize i = 0; i < n; i++) fds[i] = -1;

#ifdef __linux__
    jint *types = (*env)->GetIntArrayElements(env, jTypes, NULL);
    jlong *configs = (*env)->GetLongArrayElements(env, jConfigs, NULL);
    if (types == NULL || configs == NULL) {
        if (types) (*env)->ReleaseIntArrayElements(env, jTypes, types, JNI_ABORT);
        if (configs) (*env)->ReleaseLongArrayElements(env, jConfigs, configs, JNI_ABORT);
        (*env)->SetIntArrayRegion(env, out, 0, n, fds);
        free(fds);
        return out;
    }

    for (jsize i = 0; i < n; i++) {
        if (types[i] < 0) { fds[i] = -1; continue; }   /* slot skipped */

        struct perf_event_attr attr;
        memset(&attr, 0, sizeof(attr));
        attr.size = sizeof(attr);
        attr.type = (uint32_t) types[i];
        attr.config = (uint64_t) configs[i];
        attr.disabled = 0;
        attr.inherit  = 0;
        attr.exclude_kernel = 0;
        attr.exclude_hv = 0;
        attr.read_format = PERF_FORMAT_TOTAL_TIME_ENABLED
                         | PERF_FORMAT_TOTAL_TIME_RUNNING;

        long fd = perf_event_open(&attr, 0 /* current thread */, -1, -1, 0);
        fds[i] = (fd < 0) ? -1 : (jint) fd;
    }

    (*env)->ReleaseIntArrayElements(env, jTypes, types, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, jConfigs, configs, JNI_ABORT);
#endif

    (*env)->SetIntArrayRegion(env, out, 0, n, fds);
    free(fds);
    return out;
}

/*
 * Java signature:
 *   private static native int nativeReadAllRaw(
 *       int[] fds, long[] values, long[] enabled, long[] running);
 *
 * For each fds[i] >= 0, performs one 24-byte read() and writes the
 * three raw u64s into the parallel output arrays. NO scaling.
 *
 *   values[i]  = raw counter value
 *   enabled[i] = total time the event has been enabled
 *   running[i] = total time the event has actually been on a PMU
 *
 * For fds[i] < 0 or short reads, all three outputs are 0.
 *
 * Returns the number of slots where the read succeeded.
 *
 * The Java caller computes (afterValue - beforeValue) etc and, if
 * (afterRunning - beforeRunning) > 0, scales the delta as
 *   scaledDelta = deltaValue * deltaEnabled / deltaRunning.
 * Applying the scale to absolute values BEFORE subtracting is wrong
 * under multiplexing — see the header comment.
 */
JNIEXPORT jint JNICALL
Java_com_scott_perf_PerfBridge_nativeReadAllRaw(JNIEnv *env, jclass cls,
                                                jintArray jFds,
                                                jlongArray jValues,
                                                jlongArray jEnabled,
                                                jlongArray jRunning) {
    (void) cls;
    jsize n  = (*env)->GetArrayLength(env, jFds);
    jsize nV = (*env)->GetArrayLength(env, jValues);
    jsize nE = (*env)->GetArrayLength(env, jEnabled);
    jsize nR = (*env)->GetArrayLength(env, jRunning);
    if (nV < n) n = nV;
    if (nE < n) n = nE;
    if (nR < n) n = nR;

    jint  *fds = (*env)->GetIntArrayElements(env,  jFds,     NULL);
    jlong *v   = (*env)->GetLongArrayElements(env, jValues,  NULL);
    jlong *e   = (*env)->GetLongArrayElements(env, jEnabled, NULL);
    jlong *r   = (*env)->GetLongArrayElements(env, jRunning, NULL);
    if (fds == NULL || v == NULL || e == NULL || r == NULL) {
        if (fds) (*env)->ReleaseIntArrayElements(env,  jFds,     fds, JNI_ABORT);
        if (v)   (*env)->ReleaseLongArrayElements(env, jValues,  v,   JNI_ABORT);
        if (e)   (*env)->ReleaseLongArrayElements(env, jEnabled, e,   JNI_ABORT);
        if (r)   (*env)->ReleaseLongArrayElements(env, jRunning, r,   JNI_ABORT);
        return 0;
    }

    jint ok = 0;
#ifdef __linux__
    for (jsize i = 0; i < n; i++) {
        int fd = (int) fds[i];
        if (fd < 0) { v[i] = 0; e[i] = 0; r[i] = 0; continue; }

        struct scaled_read sr;
        ssize_t rb = read(fd, &sr, sizeof(sr));
        if (rb != (ssize_t) sizeof(sr)) {
            v[i] = 0; e[i] = 0; r[i] = 0;
            continue;
        }
        v[i] = (jlong) sr.value;
        e[i] = (jlong) sr.time_enabled;
        r[i] = (jlong) sr.time_running;
        ok++;
    }
#else
    for (jsize i = 0; i < n; i++) { v[i] = 0; e[i] = 0; r[i] = 0; }
#endif

    (*env)->ReleaseIntArrayElements(env,  jFds,     fds, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, jValues,  v,   0);
    (*env)->ReleaseLongArrayElements(env, jEnabled, e,   0);
    (*env)->ReleaseLongArrayElements(env, jRunning, r,   0);
    return ok;
}

/*
 * Java signature:
 *   private static native void nativeCloseAll(int[] fds);
 */
JNIEXPORT void JNICALL
Java_com_scott_perf_PerfBridge_nativeCloseAll(JNIEnv *env, jclass cls,
                                              jintArray jFds) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, jFds);
    jint *fds = (*env)->GetIntArrayElements(env, jFds, NULL);
    if (fds == NULL) return;

    for (jsize i = 0; i < n; i++) {
        if (fds[i] >= 0) {
            close((int) fds[i]);
            fds[i] = -1;
        }
    }

    (*env)->ReleaseIntArrayElements(env, jFds, fds, 0 /* copy back */);
}

/*
 * Java signature:
 *   private static native int nativeGetCpu();
 */
JNIEXPORT jint JNICALL
Java_com_scott_perf_PerfBridge_nativeGetCpu(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
#ifdef __linux__
    int c = sched_getcpu();
    return (jint) (c < 0 ? -1 : c);
#else
    return -1;
#endif
}

/*
 * Java signature:
 *   private static native int nativeAvailable();
 */
JNIEXPORT jint JNICALL
Java_com_scott_perf_PerfBridge_nativeAvailable(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
#ifdef __linux__
    return 1;
#else
    return 0;
#endif
}

