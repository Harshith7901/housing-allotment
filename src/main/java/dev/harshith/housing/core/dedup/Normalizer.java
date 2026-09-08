package dev.harshith.housing.core.dedup;

import dev.harshith.housing.core.util.Text;

import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Field normalisation for duplicate detection.
 *
 * <p>Normalisation is where most of the accuracy in record linkage actually comes from —
 * more than the choice of string metric. The aim is to strip away the differences that
 * come from how a form was filled in, while preserving the differences that mean two
 * different people.
 *
 * <p>Two decisions worth defending:
 *
 * <ul>
 *   <li><b>Honorifics and relationship words are removed, not scored.</b> "Smt. Lakshmi
 *       Devi W/O Ramesh" and "Lakshmi Devi" are the same person on two forms; leaving the
 *       prefix in drags the string similarity down far enough to miss the match.</li>
 *   <li><b>Name tokens are sorted for the comparison key but the original order is kept
 *       for display.</b> Given name and surname order is inconsistent across online forms
 *       and keyed paper forms, so "Ramesh Kumar" and "Kumar Ramesh" must compare as the
 *       same. Sorting the tokens does that without any locale-specific name parsing.</li>
 * </ul>
 */
public final class Normalizer {

    private static final Set<String> NOISE_TOKENS = new LinkedHashSet<>(Arrays.asList(
            "MR", "MRS", "MS", "MISS", "SHRI", "SRI", "SMT", "KUMARI", "KUM", "DR", "PROF",
            "SO", "WO", "DO", "CO", "S", "W", "D", "C", "OF", "LATE"
    ));

    private Normalizer() {
    }

    /**
     * Uppercased, accent-stripped, honorific-free, alphabetically ordered name tokens
     * joined by a single space. Empty result means the name was unusable.
     */
    public static String nameKey(String raw) {
        List<String> tokens = nameTokens(raw);
        List<String> sorted = new ArrayList<>(tokens);
        java.util.Collections.sort(sorted);
        return String.join(" ", sorted);
    }

    public static List<String> nameTokens(String raw) {
        String stripped = stripAccents(Text.upper(Text.squeeze(raw)));
        List<String> out = new ArrayList<>();
        for (String token : stripped.split("[^A-Z0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (NOISE_TOKENS.contains(token)) {
                continue;
            }
            // Single initials carry almost no discriminating power and appear
            // inconsistently between channels, so they are dropped from the key.
            if (token.length() == 1 && !Character.isDigit(token.charAt(0))) {
                continue;
            }
            out.add(token);
        }
        return out;
    }

    /** Last ten digits of the phone number, which drops country codes and leading zeros. */
    public static String phoneKey(String raw) {
        String digits = Text.digitsOnly(raw);
        return digits.length() <= 10 ? digits : digits.substring(digits.length() - 10);
    }

    /**
     * A comparable key for a government identifier.
     *
     * <p>The matcher never needs the identifier itself, only a stable token that is equal
     * for two forms carrying the same number, so this accepts either form:
     *
     * <ul>
     *   <li>A raw identifier as digits — punctuation and spacing stripped, and rejected
     *       below eight digits, which is short enough to be a partial entry rather than a
     *       real number.</li>
     *   <li>An opaque keyed digest, which is what the service layer actually stores. A
     *       digest contains letters, so it must not be run through digit stripping; it is
     *       upper-cased and passed through, and rejected below sixteen characters so a
     *       stray value cannot masquerade as one.</li>
     * </ul>
     */
    public static String governmentIdKey(String raw) {
        String trimmed = Text.squeeze(raw).replace(" ", "");
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed.length() >= 8 ? trimmed : "";
        }
        String opaque = Text.upper(trimmed).replaceAll("[^A-Z0-9]", "");
        return opaque.length() >= 16 ? opaque : "";
    }

    /** Address reduced to a set of significant tokens, for Jaccard overlap. */
    public static Set<String> addressTokens(String raw) {
        Set<String> tokens = new LinkedHashSet<>();
        String stripped = stripAccents(Text.upper(Text.squeeze(raw)));
        for (String token : stripped.split("[^A-Z0-9]+")) {
            if (token.length() < 2) {
                continue;
            }
            tokens.add(expandAbbreviation(token));
        }
        return tokens;
    }

    private static String expandAbbreviation(String token) {
        return switch (token) {
            case "RD" -> "ROAD";
            case "ST" -> "STREET";
            case "CRS", "CROSS" -> "CROSS";
            case "MN", "MAIN" -> "MAIN";
            case "APT", "APTS", "APARTMENT", "APARTMENTS" -> "APARTMENT";
            case "NGR", "NAGAR" -> "NAGAR";
            case "PO" -> "POSTOFFICE";
            case "NO", "NUM" -> "NUMBER";
            default -> token;
        };
    }

    public static String stripAccents(String s) {
        if (s == null) {
            return "";
        }
        String decomposed = java.text.Normalizer.normalize(s, Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
