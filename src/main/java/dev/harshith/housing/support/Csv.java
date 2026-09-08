package dev.harshith.housing.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comma-separated storage for the two small ordered/unordered code lists on an
 * application: its horizontal quota codes and its unit type preferences.
 *
 * <p>A child table for each would be more orthodox. It is not obviously better here: both
 * lists are short, closed sets of codes validated against the rule set, they are never
 * queried individually, and they are always read and written whole. Two extra tables and
 * two extra joins would buy nothing except the appearance of normalisation. The trade is
 * recorded in the README rather than left for a reviewer to find.
 *
 * <p>What does matter is that horizontal codes come back in a stable sorted order, because
 * they end up in the canonical roll encoding and therefore in the roll hash.
 */
public final class Csv {

    private Csv() {
    }

    public static String join(Collection<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    /** Preserves order. Use for ordered lists such as unit type preferences. */
    public static List<String> toList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Sorted, so that the encoded form is stable. Use for horizontal quota codes. */
    public static Set<String> toSortedSet(String csv) {
        return new TreeSet<>(toList(csv));
    }

    public static Set<String> toOrderedSet(String csv) {
        return new LinkedHashSet<>(toList(csv));
    }
}
