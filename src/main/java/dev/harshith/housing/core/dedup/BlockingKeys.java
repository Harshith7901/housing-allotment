package dev.harshith.housing.core.dedup;

import dev.harshith.housing.core.util.Text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates the blocking keys for one record. Two records are compared only if they share
 * at least one key.
 *
 * <p>Several keys are emitted per record on purpose, each designed to survive a different
 * kind of form error, so that a duplicate has to defeat all of them to escape detection:
 *
 * <ul>
 *   <li>{@code GID} — same government identifier. Defeats every spelling error at once.</li>
 *   <li>{@code PHN} — same last ten digits of the phone number. Survives a completely
 *       differently spelled name.</li>
 *   <li>{@code SDX} — soundex of the first name token plus year of birth. Survives a
 *       misspelled name and a wrong phone number.</li>
 *   <li>{@code NDB} — name key prefix plus full date of birth. Survives a missing phone
 *       number and a name whose tokens were entered in a different order.</li>
 *   <li>{@code WRD} — ward plus soundex of the last name token. Catches the case where a
 *       clerk keyed a different date of birth from a hard-to-read form.</li>
 * </ul>
 */
public final class BlockingKeys {

    private BlockingKeys() {
    }

    public static Set<String> of(Dedup.ApplicantRecord r) {
        Set<String> keys = new LinkedHashSet<>();

        String gid = Normalizer.governmentIdKey(r.governmentId());
        if (!gid.isEmpty()) {
            keys.add("GID:" + gid);
        }

        String phone = Normalizer.phoneKey(r.phone());
        if (phone.length() == 10) {
            keys.add("PHN:" + phone);
        }

        List<String> tokens = Normalizer.nameTokens(r.fullName());
        String nameKey = Normalizer.nameKey(r.fullName());
        String year = r.dateOfBirth() == null ? "" : String.valueOf(r.dateOfBirth().getYear());

        if (!tokens.isEmpty() && !year.isEmpty()) {
            keys.add("SDX:" + Soundex.of(tokens.get(0)) + ":" + year);
        }
        // Measure the stripped form, not the spaced one. nameTokens keeps single-digit
        // tokens, so "1 2 3" yields a five-character nameKey whose stripped form is only
        // three characters long - and a blocking key that throws on one absurd but legal
        // name would abort the deduplication of the entire scheme.
        String namePrefixSource = Text.alphanumUpper(nameKey);
        if (namePrefixSource.length() >= 4 && r.dateOfBirth() != null) {
            keys.add("NDB:" + namePrefixSource.substring(0, 4) + ":" + r.dateOfBirth());
        }
        if (!tokens.isEmpty() && !Text.isBlank(r.wardCode())) {
            keys.add("WRD:" + Text.upper(r.wardCode().trim()) + ":" + Soundex.of(tokens.get(tokens.size() - 1)));
        }
        return keys;
    }
}
