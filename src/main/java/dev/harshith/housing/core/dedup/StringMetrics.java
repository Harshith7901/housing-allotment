package dev.harshith.housing.core.dedup;

import java.util.HashSet;
import java.util.Set;

/**
 * The three string metrics the matcher needs, implemented here rather than pulled from a
 * library.
 *
 * <p>That is a deliberate trade. A dependency would be shorter, but these functions are
 * the numeric core of a decision that displaces families from a housing list, and every
 * threshold in {@link MatchScorer} is calibrated against these exact definitions. Having
 * them in the repository means the arithmetic behind a contested match can be read,
 * traced and re-run by anyone reviewing the scheme, and it cannot change under the system
 * because a transitive dependency was upgraded.
 */
public final class StringMetrics {

    private StringMetrics() {
    }

    /**
     * Jaro–Winkler similarity in [0,1].
     *
     * <p>Chosen over plain edit distance for names because it weights agreement at the
     * start of the string and tolerates transposed characters, which is the dominant error
     * mode when a clerk keys a handwritten name: "RAMESH" / "RAMHES" scores 0.96 here and
     * 0.67 under a normalised Levenshtein.
     */
    public static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        if (jaro < 0.7) {
            // Winkler's own guidance: do not apply the prefix bonus to weak matches,
            // otherwise unrelated names sharing a first syllable score misleadingly high.
            return jaro;
        }
        int prefix = 0;
        int max = Math.min(4, Math.min(a.length(), b.length()));
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    public static double jaro(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }

        int window = Math.max(a.length(), b.length()) / 2 - 1;
        if (window < 0) {
            window = 0;
        }
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];

        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (bMatched[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatched[i] = true;
                bMatched[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        return ((m / a.length()) + (m / b.length()) + ((m - transpositions / 2.0) / m)) / 3.0;
    }

    /** Classic Levenshtein edit distance, two-row implementation. */
    public static int levenshtein(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Jaccard overlap of two token sets, in [0,1]. Two empty sets score 0, not 1. */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
