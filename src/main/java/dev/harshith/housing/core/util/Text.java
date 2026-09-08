package dev.harshith.housing.core.util;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Locale-independent text helpers.
 *
 * <p>Every string that ends up inside a hashed artefact goes through here first.
 * {@link String#toUpperCase()} without an explicit locale is a genuine hazard: under a
 * Turkish default locale it maps 'i' to a dotted capital I, which would silently change
 * a roll hash depending on the server's locale. Nothing in an auditable pipeline may
 * depend on the machine it ran on.
 */
public final class Text {

    private Text() {
    }

    public static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Collapses all whitespace runs to a single space and trims. */
    public static String squeeze(String s) {
        return trimToEmpty(s).replaceAll("\\s+", " ");
    }

    /** Keeps only A-Z and 0-9, uppercased. Used for name and address comparison keys. */
    public static String alphanumUpper(String s) {
        return upper(s).replaceAll("[^A-Z0-9]", "");
    }

    /** Keeps only digits. Used for phone and government-ID normalisation. */
    public static String digitsOnly(String s) {
        return trimToEmpty(s).replaceAll("\\D", "");
    }

    /**
     * Renders a percentage at a fixed scale, so that the same rule set always encodes to
     * the same bytes whether it came from a JSON body, a SQL row, or a test fixture.
     *
     * <p>It refuses to round rather than accepting more precision than it records. Quietly
     * rounding 15.00001% to 15.0000% would change a published quota, and would do it
     * inside the function whose output is hashed. The rule set constructors reject
     * over-precise percentages at construction, so this should be unreachable; the
     * conversion to {@link IllegalArgumentException} exists so that if some other path
     * ever reaches it, the caller gets a bad-request with a usable message rather than an
     * {@code ArithmeticException} surfacing as a 500.
     */
    public static String percent(BigDecimal value) {
        try {
            return value.setScale(4, java.math.RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException("a published percentage may carry at most 4 decimal "
                    + "places; " + value.toPlainString() + " cannot be recorded exactly", tooPrecise);
        }
    }
}
