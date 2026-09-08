package dev.harshith.housing.core.dedup;

/**
 * Soundex, used only as a <em>blocking</em> key — never as a matching decision.
 *
 * <p>The distinction matters. Comparing 4,000 applications pairwise is 8 million
 * comparisons, which is cheap here but grows quadratically and would not survive a
 * city-wide scheme. Blocking splits records into buckets that plausible duplicates must
 * share, and only records inside a bucket are compared, turning the problem near-linear.
 *
 * <p>Soundex is a poor similarity measure — it happily collapses distinct names — but that
 * is acceptable and even desirable for a recall-oriented blocking key: a false bucket-mate
 * costs one extra comparison, whereas a missed bucket costs a missed duplicate, and a
 * missed duplicate means one family holding two lottery tickets.
 */
public final class Soundex {

    private Soundex() {
    }

    public static String of(String input) {
        String s = Normalizer.stripAccents(input == null ? "" : input)
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z]", "");
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(s.charAt(0));
        char previousCode = code(s.charAt(0));
        for (int i = 1; i < s.length() && out.length() < 4; i++) {
            char c = s.charAt(i);
            char code = code(c);
            if (code == '0') {
                // H and W are transparent: they do not separate two identical codes.
                if (c != 'H' && c != 'W') {
                    previousCode = '0';
                }
                continue;
            }
            if (code != previousCode) {
                out.append(code);
            }
            previousCode = code;
        }
        while (out.length() < 4) {
            out.append('0');
        }
        return out.toString();
    }

    private static char code(char c) {
        return switch (c) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            default -> '0';
        };
    }
}
