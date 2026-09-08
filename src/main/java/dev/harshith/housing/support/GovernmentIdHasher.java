package dev.harshith.housing.support;

import dev.harshith.housing.core.util.Hashing;
import dev.harshith.housing.core.util.Text;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a government identifier into a keyed digest, so the database never holds the
 * number itself.
 *
 * <p>The pepper is essential and is the reason this is a bean rather than a static
 * utility. A plain, unsalted SHA-256 of a twelve-digit identifier is not a protection at
 * all: the entire number space is enumerable in minutes, so an attacker with the table
 * can recover every identifier by brute force. A secret pepper that never leaves the
 * application's configuration makes the digests useless without it, while still supporting
 * the only operation the system actually needs — telling whether two forms carry the same
 * identifier.
 *
 * <p>Consequences, stated because they are real and not incidental:
 *
 * <ul>
 *   <li>Rotating the pepper invalidates every stored digest, so rotation means a
 *       re-hashing migration run from the source documents. It is not a routine act.</li>
 *   <li>Because the pepper is a single secret, this only protects against an attacker who
 *       gets the database. It does not protect against one who gets the application
 *       configuration as well. An HSM or an external tokenisation service is the honest
 *       answer at scale, and is listed as an omission in the README.</li>
 * </ul>
 */
@Component
public class GovernmentIdHasher {

    private final String pepper;

    public GovernmentIdHasher(@Value("${housing.government-id.pepper:development-only-pepper}") String pepper) {
        this.pepper = pepper;
    }

    /** Empty string for a missing or unusably short identifier; never null. */
    public String hash(String rawIdentifier) {
        String digits = Text.digitsOnly(rawIdentifier);
        if (digits.length() < 8) {
            return "";
        }
        return Hashing.sha256Hex("gid/1|" + pepper + "|" + digits);
    }

    /**
     * The last four digits, kept in clear so that a clerk can confirm a number against a
     * physical document. Four digits out of twelve is not identifying on its own and is
     * the standard compromise for exactly this purpose.
     */
    public String last4(String rawIdentifier) {
        String digits = Text.digitsOnly(rawIdentifier);
        return digits.length() < 4 ? "" : digits.substring(digits.length() - 4);
    }
}
