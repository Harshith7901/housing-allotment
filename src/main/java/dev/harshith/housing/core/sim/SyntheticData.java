package dev.harshith.housing.core.sim;

import dev.harshith.housing.core.dedup.Dedup;
import dev.harshith.housing.core.model.Channel;
import dev.harshith.housing.core.units.Units;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic synthetic dataset: about 4,000 applications for 600 flats, including
 * genuine duplicates keyed in with realistic transcription errors.
 *
 * <p>This exists because the interesting failures of this system are all population
 * effects, and none of them appear in a fixture of six rows: a quota that under-fills, a
 * horizontal minimum that forces a displacement, a duplicate family that defeats one
 * blocking key but not another, a lapsed reserved seat. The generator produces those on
 * purpose.
 *
 * <p>The generator is seeded, so the dataset is reproducible — but note that
 * {@link Random} appears <b>only here</b>, in test data generation, and never anywhere in
 * the draw. The draw's randomness comes from the published commit–reveal seed and SHA-256,
 * which is what makes it verifiable by someone who does not run this code.
 */
public final class SyntheticData {

    private static final String[] GIVEN_NAMES = {
            "RAMESH", "LAKSHMI", "SURESH", "PRIYA", "ANIL", "MEENA", "VENKAT", "GEETHA",
            "MANOJ", "SHANTHI", "RAJESH", "KAVITA", "DEEPAK", "SUNITA", "ARUN", "REKHA",
            "HARISH", "USHA", "PRAKASH", "NALINI", "MOHAN", "SAROJA", "GANESH", "VIMALA",
            "SRIDHAR", "PADMA", "KIRAN", "ANITHA", "NAGARAJ", "BHARATHI"
    };

    private static final String[] FAMILY_NAMES = {
            "KUMAR", "REDDY", "SHARMA", "IYER", "NAIR", "GOWDA", "PATIL", "RAO",
            "SHETTY", "MURTHY", "BHAT", "PILLAI", "DESAI", "JOSHI", "MENON", "VERMA"
    };

    private static final String[] STREETS = {
            "MAIN ROAD", "2ND CROSS", "TEMPLE STREET", "MARKET ROAD", "LAKE VIEW ROAD",
            "5TH CROSS", "SCHOOL STREET", "STATION ROAD", "GARDEN LANE", "MILL ROAD"
    };

    private static final String[] LOCALITIES = {
            "GANDHI NAGAR", "SHANTHI NAGAR", "NEW COLONY", "INDUSTRIAL LAYOUT",
            "OLD TOWN", "RIVERSIDE", "HILL VIEW", "KRISHNA NAGAR"
    };

    /** Wards inside the scheme area; applicants from elsewhere get ward {@code OUT-nn}. */
    private static final String[] SCHEME_WARDS = {"W-11", "W-12", "W-13", "W-14"};

    private SyntheticData() {
    }

    /**
     * @param sourceApplicationId for a re-submission, the application id of the household's
     *                            first form; null for a first form. This is ground truth
     *                            that only the generator knows and the matcher never sees;
     *                            the simulation uses it to measure the matcher's precision
     *                            and recall rather than merely counting merges.
     */
    public record SyntheticApplication(
            Dedup.ApplicantRecord identity,
            String verticalCode,
            Set<String> horizontalCodes,
            int residencyYears,
            List<String> unitTypePreferences,
            boolean eligible,
            String ineligibilityReason,
            String sourceApplicationId
    ) {
        /** The true household this form belongs to. */
        public String householdKey() {
            return sourceApplicationId == null ? identity().applicationId() : sourceApplicationId;
        }
    }

    public record Dataset(List<SyntheticApplication> applications,
                          List<Units.UnitInventoryItem> inventory,
                          int intendedHouseholds,
                          int intendedDuplicates) {
    }

    public static Dataset generate(long seed, int households, int duplicates, int units) {
        Random rnd = new Random(seed);
        Instant windowOpens = Instant.parse("2026-04-01T04:00:00Z");

        List<SyntheticApplication> applications = new ArrayList<>();
        List<SyntheticApplication> originals = new ArrayList<>();

        for (int i = 0; i < households; i++) {
            SyntheticApplication app = household(rnd, i, windowOpens);
            applications.add(app);
            originals.add(app);
        }

        // Re-submissions. The same household fills the form again, usually on the other
        // channel, and the second form carries errors.
        for (int i = 0; i < duplicates; i++) {
            SyntheticApplication original = originals.get(rnd.nextInt(originals.size()));
            applications.add(resubmission(rnd, original, households + i, windowOpens));
        }

        List<Units.UnitInventoryItem> inventory = new ArrayList<>(units);
        for (int i = 0; i < units; i++) {
            String block = "B" + (char) ('A' + (i / 60));
            String type = i % 5 == 0 ? "THREE_BHK" : (i % 2 == 0 ? "TWO_BHK" : "ONE_BHK");
            inventory.add(new Units.UnitInventoryItem(
                    String.format("U-%04d", i + 1), block, type, (i % 6) + 1));
        }

        return new Dataset(applications, inventory, households, duplicates);
    }

    private static SyntheticApplication household(Random rnd, int index, Instant windowOpens) {
        String applicationId = String.format("APP-2026-%05d", index + 1);
        String given = GIVEN_NAMES[rnd.nextInt(GIVEN_NAMES.length)];
        String family = FAMILY_NAMES[rnd.nextInt(FAMILY_NAMES.length)];
        String relative = GIVEN_NAMES[rnd.nextInt(GIVEN_NAMES.length)] + " " + family;

        boolean local = rnd.nextInt(100) < 55;
        String ward = local
                ? SCHEME_WARDS[rnd.nextInt(SCHEME_WARDS.length)]
                : String.format("OUT-%02d", rnd.nextInt(40) + 1);
        int residencyYears = local ? rnd.nextInt(25) : rnd.nextInt(3);

        String address = (rnd.nextInt(180) + 1) + ", " + STREETS[rnd.nextInt(STREETS.length)]
                + ", " + LOCALITIES[rnd.nextInt(LOCALITIES.length)];

        LocalDate dob = LocalDate.of(1960 + rnd.nextInt(45), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));

        // A tenth of applicants have no usable government identifier on the form, which is
        // what forces the matcher to work probabilistically rather than on keys alone.
        String governmentId = rnd.nextInt(100) < 90
                ? String.valueOf(100_000_000_000L + (long) (rnd.nextDouble() * 800_000_000_000L))
                : "";
        String phone = rnd.nextInt(100) < 95
                ? "9" + (100_000_000 + rnd.nextInt(800_000_000))
                : "";

        Channel channel = rnd.nextInt(100) < 62 ? Channel.ONLINE : Channel.PAPER_KEYED;
        Instant submittedAt = windowOpens.plus(rnd.nextInt(30 * 24 * 60), ChronoUnit.MINUTES);

        String vertical = pickVertical(rnd);
        Set<String> horizontals = pickHorizontals(rnd);
        List<String> preferences = pickPreferences(rnd);

        boolean eligible = rnd.nextInt(1000) >= 45;
        String reason = eligible ? null
                : (rnd.nextBoolean()
                        ? "INCOME_ABOVE_CEILING: declared income exceeds the published ceiling for this scheme"
                        : "EXISTING_PROPERTY: applicant already owns a unit allotted under an earlier scheme");

        return new SyntheticApplication(
                new Dedup.ApplicantRecord(applicationId, channel, submittedAt,
                        given + " " + family, relative, governmentId, phone, dob, address, ward),
                vertical, horizontals, residencyYears, preferences, eligible, reason, null);
    }

    private static SyntheticApplication resubmission(Random rnd,
                                                     SyntheticApplication original,
                                                     int index,
                                                     Instant windowOpens) {
        Dedup.ApplicantRecord o = original.identity();
        String applicationId = String.format("APP-2026-%05d", index + 1);

        Channel channel = o.channel() == Channel.ONLINE ? Channel.PAPER_KEYED : Channel.ONLINE;
        String name = o.fullName();
        String phone = o.phone();
        String governmentId = o.governmentId();
        LocalDate dob = o.dateOfBirth();
        String address = o.addressLine();

        int mutations = 1 + rnd.nextInt(3);
        for (int i = 0; i < mutations; i++) {
            switch (rnd.nextInt(6)) {
                case 0 -> name = transposeTwoLetters(rnd, name);
                case 1 -> name = dropALetter(rnd, name);
                case 2 -> phone = phone.isEmpty() ? phone : mutateOneDigit(rnd, phone);
                case 3 -> governmentId = rnd.nextInt(100) < 70 ? "" : governmentId;
                case 4 -> dob = transposeDayAndMonth(dob);
                default -> address = address.replace("ROAD", "RD").replace(", ", " ");
            }
        }

        Instant submittedAt = o.submittedAt() == null
                ? windowOpens
                : o.submittedAt().plus(1 + rnd.nextInt(20 * 24 * 60), ChronoUnit.MINUTES);

        return new SyntheticApplication(
                new Dedup.ApplicantRecord(applicationId, channel, submittedAt,
                        name, o.relativeName(), governmentId, phone, dob, address, o.wardCode()),
                original.verticalCode(), original.horizontalCodes(), original.residencyYears(),
                original.unitTypePreferences(), original.eligible(), original.ineligibilityReason(),
                original.householdKey());
    }

    private static String pickVertical(Random rnd) {
        int r = rnd.nextInt(100);
        if (r < 14) {
            return "CAT_A";
        }
        if (r < 22) {
            return "CAT_B";
        }
        if (r < 47) {
            return "CAT_C";
        }
        return "OPEN";
    }

    private static Set<String> pickHorizontals(Random rnd) {
        Set<String> out = new LinkedHashSet<>();
        if (rnd.nextInt(100) < 34) {
            out.add("WOMEN");
        }
        if (rnd.nextInt(100) < 4) {
            out.add("PWD");
        }
        if (rnd.nextInt(100) < 3) {
            out.add("EX_SERVICE");
        }
        if (rnd.nextInt(100) < 9) {
            out.add("SENIOR");
        }
        return out;
    }

    private static List<String> pickPreferences(Random rnd) {
        List<String> all = new ArrayList<>(List.of("ONE_BHK", "TWO_BHK", "THREE_BHK"));
        List<String> out = new ArrayList<>();
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count && !all.isEmpty(); i++) {
            out.add(all.remove(rnd.nextInt(all.size())));
        }
        return out;
    }

    private static String transposeTwoLetters(Random rnd, String s) {
        if (s.length() < 4) {
            return s;
        }
        int i = 1 + rnd.nextInt(s.length() - 2);
        char[] chars = s.toCharArray();
        char tmp = chars[i];
        chars[i] = chars[i + 1];
        chars[i + 1] = tmp;
        return new String(chars);
    }

    private static String dropALetter(Random rnd, String s) {
        if (s.length() < 4) {
            return s;
        }
        int i = 1 + rnd.nextInt(s.length() - 2);
        return s.substring(0, i) + s.substring(i + 1);
    }

    private static String mutateOneDigit(Random rnd, String s) {
        if (s.length() < 2) {
            return s;
        }
        int i = 1 + rnd.nextInt(s.length() - 1);
        char replacement = (char) ('0' + rnd.nextInt(10));
        return s.substring(0, i) + replacement + s.substring(i + 1);
    }

    private static LocalDate transposeDayAndMonth(LocalDate d) {
        if (d == null || d.getDayOfMonth() > 12) {
            return d;
        }
        return LocalDate.of(d.getYear(), d.getDayOfMonth(), d.getMonthValue());
    }
}
