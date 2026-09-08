package dev.harshith.housing.core.dedup;

import java.util.HashMap;
import java.util.Map;

/**
 * Disjoint-set union over application ids, with path compression and union by size.
 *
 * <p>Duplicate links are transitive in practice: a family that applied three times
 * produces links A–B and B–C but often no direct A–C link, because the third form was
 * keyed from a different piece of paper with different errors. Treating links pairwise
 * would leave that household with two tickets. Union-find gives the transitive closure in
 * effectively linear time.
 *
 * <p>The transitivity is also the reason the auto-link threshold is set as high as 0.93:
 * transitive closure turns any single false link into a merge of two entire households,
 * so the bar for linking without a human has to be well above the bar for suspicion.
 */
public final class UnionFind {

    private final Map<String, String> parent = new HashMap<>();
    private final Map<String, Integer> size = new HashMap<>();

    public void add(String id) {
        parent.putIfAbsent(id, id);
        size.putIfAbsent(id, 1);
    }

    public String find(String id) {
        add(id);
        String root = id;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // Path compression, iterative to avoid deep recursion on pathological chains.
        String cursor = id;
        while (!parent.get(cursor).equals(root)) {
            String next = parent.get(cursor);
            parent.put(cursor, root);
            cursor = next;
        }
        return root;
    }

    public void union(String a, String b) {
        String rootA = find(a);
        String rootB = find(b);
        if (rootA.equals(rootB)) {
            return;
        }
        // Union by size keeps the trees shallow. Where sizes are equal the lexicographically
        // smaller id wins, so the structure does not depend on insertion order.
        int sizeA = size.get(rootA);
        int sizeB = size.get(rootB);
        String winner;
        String loser;
        if (sizeA > sizeB || (sizeA == sizeB && rootA.compareTo(rootB) <= 0)) {
            winner = rootA;
            loser = rootB;
        } else {
            winner = rootB;
            loser = rootA;
        }
        parent.put(loser, winner);
        size.put(winner, sizeA + sizeB);
    }
}
