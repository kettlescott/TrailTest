package com.scott;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Best-effort NUMA topology inspector.
 *
 * <p>Reads {@code /sys/devices/system/node/nodeN/cpulist} at initialization
 * and exposes:
 * <ul>
 *   <li>{@link #nodeCount()} — number of NUMA nodes visible to the kernel</li>
 *   <li>{@link #cpusOfNode(int)} — logical CPU IDs belonging to a node</li>
 *   <li>{@link #nodeOfCpu(int)} — reverse lookup, {@code -1} if unknown</li>
 * </ul>
 *
 * <p>Pure Java, no libnuma dependency, no JNI. On non-Linux hosts (or if
 * sysfs is unavailable) {@link #isAvailable()} returns {@code false} and
 * the query methods degrade gracefully: {@code nodeCount() == 0},
 * {@code cpusOfNode(n)} returns an empty array, {@code nodeOfCpu(cpu)}
 * returns {@code -1}.
 *
 * <p>Called once at process startup to build {@code cpuToNode[]} — no
 * hot-path use.
 */
public final class NumaTopology {

    private static final NumaTopology INSTANCE = load();

    private final boolean available;
    private final int nodeCount;
    private final int[][] cpusOfNode;   // cpusOfNode[node] = sorted logical CPU ids
    private final int[]   cpuToNode;    // cpuToNode[cpu]   = node id, or -1

    public static NumaTopology get() { return INSTANCE; }

    public boolean isAvailable()      { return available; }
    public int  nodeCount()           { return nodeCount; }
    public int[] cpusOfNode(int node) {
        if (!available || node < 0 || node >= nodeCount) return new int[0];
        return cpusOfNode[node].clone();
    }
    public int nodeOfCpu(int cpu) {
        if (!available || cpu < 0 || cpu >= cpuToNode.length) return -1;
        return cpuToNode[cpu];
    }

    /* -------------------------------------------------------------------- */

    private NumaTopology(boolean available, int nodeCount, int[][] cpusOfNode, int[] cpuToNode) {
        this.available  = available;
        this.nodeCount  = nodeCount;
        this.cpusOfNode = cpusOfNode;
        this.cpuToNode  = cpuToNode;
    }

    private static NumaTopology unavailable() {
        return new NumaTopology(false, 0, new int[0][], new int[0]);
    }

    private static NumaTopology load() {
        Path base = Paths.get("/sys/devices/system/node");
        if (!Files.isDirectory(base)) return unavailable();

        // Enumerate nodeN directories.
        List<Integer> nodes = new ArrayList<>();
        try (var stream = Files.list(base)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith("node")) {
                    try {
                        nodes.add(Integer.parseInt(name.substring(4)));
                    } catch (NumberFormatException ignored) { /* skip */ }
                }
            });
        } catch (IOException e) {
            return unavailable();
        }
        if (nodes.isEmpty()) return unavailable();
        java.util.Collections.sort(nodes);
        int nc = nodes.get(nodes.size() - 1) + 1;

        int[][] perNode = new int[nc][];
        int maxCpu = -1;
        for (int n : nodes) {
            Path cpulist = base.resolve("node" + n).resolve("cpulist");
            try {
                String s = Files.readString(cpulist).trim();
                int[] cpus = parseCpulist(s);
                perNode[n] = cpus;
                for (int c : cpus) if (c > maxCpu) maxCpu = c;
            } catch (IOException e) {
                perNode[n] = new int[0];
            }
        }
        // Fill any gaps for nodes we didn't see.
        for (int i = 0; i < nc; i++) if (perNode[i] == null) perNode[i] = new int[0];

        int[] reverse = new int[maxCpu + 1];
        Arrays.fill(reverse, -1);
        for (int n = 0; n < nc; n++) {
            for (int c : perNode[n]) reverse[c] = n;
        }
        return new NumaTopology(true, nc, perNode, reverse);
    }

    /**
     * Parses a Linux-format CPU list like {@code "0-3,7,9-11"} into a
     * sorted int[]. Used for both {@code /sys/.../cpulist} and YAML
     * shorthand ({@link PinningConfig}).
     */
    public static int[] parseCpulist(String s) {
        if (s == null || s.isBlank()) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash < 0) {
                out.add(Integer.parseInt(part));
            } else {
                int lo = Integer.parseInt(part.substring(0, dash).trim());
                int hi = Integer.parseInt(part.substring(dash + 1).trim());
                for (int i = lo; i <= hi; i++) out.add(i);
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        Arrays.sort(r);
        return r;
    }

    /** Short human-readable topology summary for run logs. */
    public String describe() {
        if (!available) return "NUMA: unavailable (non-Linux or /sys not readable)";
        StringBuilder sb = new StringBuilder("NUMA: nodes=").append(nodeCount);
        for (int n = 0; n < nodeCount; n++) {
            sb.append(", node").append(n).append("=").append(cpusOfNode[n].length).append(" CPUs");
        }
        return sb.toString();
    }
}

