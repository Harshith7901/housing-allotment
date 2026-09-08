package dev.harshith.housing.core;

import dev.harshith.housing.core.dedup.Dedup;
import dev.harshith.housing.core.dedup.Deduplicator;
import dev.harshith.housing.core.dedup.MatchScorer;
import dev.harshith.housing.core.dedup.Normalizer;
import dev.harshith.housing.core.dedup.Soundex;
import dev.harshith.housing.core.dedup.StringMetrics;
import dev.harshith.housing.core.model.Channel;
import dev.harshith.housing.core.sim.SyntheticData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplicate detection, tested for both of the mistakes it can make.
 *
 * <p>The two errors are not symmetric and the tests are not either. Missing a duplicate
 * gives one household two lottery tickets — unfair, and detectable afterwards. Wrongly
 * merging two households removes a real family from the draw, and they find out when the
 * list is published. So recall is asserted as a rate, and precision is asserted absolutely.
 */
class DeduplicatorTest {

    private static final Instant WINDOW = Instant.parse("2026-04-01T04:00:00Z");

    // ------------------------------------------------------------------ precision

    @Test
    @DisplayName("no cluster ever merges two genuinely different households")
    void precisionIsAbsoluteOnFourThousandApplications() {
        SyntheticData.Dataset data = SyntheticData.generate(20260401L, 3_600, 420, 600);
        Map<String, String> trueHousehold = data.applications().stream()
                .collect(Collectors.toMap(a -> a.identity().applicationId(),
                        SyntheticData.SyntheticApplication::householdKey));

        Dedup.DedupReport report = Deduplicator.run(identities(data), Dedup.MatchWeights.standard());

        for (Dedup.Cluster cluster : report.clusters()) {
            Set<String> households = cluster.memberApplicationIds().stream()
                    .map(trueHousehold::get)
                    .collect(Collectors.toSet());
            assertEquals(1, households.size(),
                    "cluster " + cluster.clusterId() + " merged applications from " + households);
        }
    }

    @Test
    @DisplayName("a father and son sharing a name, address and ward are never auto-linked")
    void differentIdentifiersVetoAnAutoLink() {
        // Everything agrees except the government identifier. This is a real pattern -
        // two generations at one address, often with the same given name - and merging
        // them would drop a household from the draw.
        Dedup.ApplicantRecord father = record("APP-1", Channel.ONLINE, 0,
                "RAMESH KUMAR", "SURESH KUMAR", "111111111111", "9876543210",
                LocalDate.of(1970, 3, 14), "12, MAIN ROAD, GANDHI NAGAR", "W-11");
        Dedup.ApplicantRecord son = record("APP-2", Channel.ONLINE, 60,
                "RAMESH KUMAR", "SURESH KUMAR", "222222222222", "9876543210",
                LocalDate.of(1970, 3, 14), "12, MAIN ROAD, GANDHI NAGAR", "W-11");

        Dedup.CandidatePair pair = MatchScorer.score(father, son, Dedup.MatchWeights.standard(), List.of());

        assertNotEquals(Dedup.MatchDecision.AUTO_LINKED, pair.decision(),
                "conflicting government identifiers must veto an automatic merge");
        assertEquals(Dedup.MatchDecision.NEEDS_REVIEW, pair.decision(),
                "it is still a plausible match, so it belongs in front of a human");
        assertTrue(pair.rationale().contains("vetoed") || pair.rationale().contains("differ"),
                "the veto must be stated in the record: " + pair.rationale());
    }

    @Test
    @DisplayName("the same identifier links two forms however differently the name was keyed")
    void identicalIdentifierIsDecisive() {
        Dedup.ApplicantRecord online = record("APP-1", Channel.ONLINE, 0,
                "LAKSHMI DEVI", "RAMESH", "345678901234", "9812345678",
                LocalDate.of(1982, 7, 9), "45, TEMPLE STREET", "W-12");
        Dedup.ApplicantRecord paper = record("APP-2", Channel.PAPER_KEYED, 500,
                "L DEVIE", "", "3456 7890 1234", "",
                null, "", "");

        Dedup.CandidatePair pair = MatchScorer.score(online, paper, Dedup.MatchWeights.standard(), List.of());

        assertEquals(Dedup.MatchDecision.AUTO_LINKED, pair.decision());
        assertEquals(Dedup.MatchMethod.DETERMINISTIC_GOVERNMENT_ID, pair.method());
        assertEquals(1.0, pair.score());
    }

    // ------------------------------------------------------------------ recall

    @Test
    @DisplayName("most planted re-submissions are caught without a human, and the rest are queued")
    void recallOnRealisticTranscriptionErrors() {
        SyntheticData.Dataset data = SyntheticData.generate(20260401L, 3_600, 420, 600);
        Dedup.DedupReport report = Deduplicator.run(identities(data), Dedup.MatchWeights.standard());

        double recall = (double) report.duplicateApplicationCount() / data.intendedDuplicates();
        assertTrue(recall >= 0.80,
                "caught only " + Math.round(recall * 100) + "% of planted duplicates automatically");
        assertTrue(report.needsReview().size() < data.intendedDuplicates(),
                "a review queue larger than the duplicate count is not a queue, it is a re-run by hand");
    }

    @Test
    @DisplayName("a missing field redistributes its weight instead of counting as disagreement")
    void missingFieldsDoNotPenaliseAMatch() {
        // The paper form has no phone number and no address - common, because those boxes
        // are often left blank. Treating blanks as disagreement is the classic record
        // linkage bug and it under-links precisely the channel that needs help most.
        Dedup.ApplicantRecord complete = record("APP-1", Channel.ONLINE, 0,
                "VENKAT RAO", "GANESH RAO", "", "9800011122",
                LocalDate.of(1979, 11, 2), "8, LAKE VIEW ROAD, OLD TOWN", "W-13");
        Dedup.ApplicantRecord sparse = record("APP-2", Channel.PAPER_KEYED, 900,
                "VENKAT RAO", "GANESH RAO", "", "",
                LocalDate.of(1979, 11, 2), "", "W-13");

        Dedup.CandidatePair pair = MatchScorer.score(complete, sparse, Dedup.MatchWeights.standard(), List.of());

        assertTrue(pair.score() > 0.90,
                "identical name, relative, date of birth and ward should score high, not be dragged "
                        + "down by blanks; scored " + pair.score());
        assertTrue(pair.features().stream()
                        .anyMatch(f -> f.feature().equals("phone") && !f.available()),
                "the missing field must be recorded as missing rather than as a zero");
    }

    @Test
    @DisplayName("a transposed day and month is recognised as the same date of birth")
    void dateTranspositionIsTolerated() {
        Dedup.ApplicantRecord first = record("APP-1", Channel.ONLINE, 0,
                "MEENA IYER", "ANIL IYER", "", "9700011122",
                LocalDate.of(1988, 4, 11), "3, GARDEN LANE", "W-14");
        Dedup.ApplicantRecord keyed = record("APP-2", Channel.PAPER_KEYED, 100,
                "MEENA IYER", "ANIL IYER", "", "9700011122",
                LocalDate.of(1988, 11, 4), "3, GARDEN LANE", "W-14");

        Dedup.CandidatePair pair = MatchScorer.score(first, keyed, Dedup.MatchWeights.standard(), List.of());
        assertEquals(Dedup.MatchDecision.AUTO_LINKED, pair.decision(),
                "same phone and a transposed birth date is the same person; scored " + pair.score());
    }

    // ------------------------------------------------------------------ structure

    @Test
    @DisplayName("three forms linked in a chain become one household, not two")
    void linksAreTransitive() {
        // A links to B on the phone number, B links to C on the identifier, and A and C
        // share nothing directly. All three are one family.
        List<Dedup.ApplicantRecord> records = List.of(
                record("APP-1", Channel.ONLINE, 0, "PRIYA NAIR", "MOHAN NAIR",
                        "", "9900011122", LocalDate.of(1990, 1, 15), "7, MILL ROAD", "W-11"),
                record("APP-2", Channel.PAPER_KEYED, 100, "PRIYA NAIR", "MOHAN NAIR",
                        "555566667777", "9900011122", LocalDate.of(1990, 1, 15), "7, MILL ROAD", "W-11"),
                record("APP-3", Channel.ONLINE, 200, "P NAIR", "",
                        "555566667777", "", null, "", ""));

        Dedup.DedupReport report = Deduplicator.run(records, Dedup.MatchWeights.standard());

        assertEquals(1, report.clusters().size(), "the three forms are one household");
        assertEquals(3, report.clusters().get(0).memberApplicationIds().size());
        assertEquals("APP-1", report.clusters().get(0).primaryApplicationId(),
                "the earliest submission carries the ticket");
    }

    @Test
    @DisplayName("the earliest submission carries the household's ticket")
    void earliestSubmissionSurvives() {
        List<Dedup.ApplicantRecord> records = List.of(
                record("APP-9", Channel.PAPER_KEYED, 0, "ARUN DESAI", "KIRAN DESAI",
                        "121212121212", "9600011122", LocalDate.of(1975, 6, 6), "1, MAIN ROAD", "W-11"),
                record("APP-1", Channel.ONLINE, 5_000, "ARUN DESAI", "KIRAN DESAI",
                        "121212121212", "9600011122", LocalDate.of(1975, 6, 6), "1, MAIN ROAD", "W-11"));

        Dedup.DedupReport report = Deduplicator.run(records, Dedup.MatchWeights.standard());

        assertEquals(1, report.clusters().size());
        assertEquals("APP-9", report.clusters().get(0).primaryApplicationId(),
                "APP-9 was keyed from a form submitted first, even though its id sorts later");
    }

    @Test
    @DisplayName("the report is byte-identical across runs and independent of input order")
    void deduplicationIsDeterministic() {
        SyntheticData.Dataset data = SyntheticData.generate(7L, 500, 60, 100);
        List<Dedup.ApplicantRecord> forward = identities(data);
        List<Dedup.ApplicantRecord> reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);

        Dedup.DedupReport a = Deduplicator.run(forward, Dedup.MatchWeights.standard());
        Dedup.DedupReport b = Deduplicator.run(reversed, Dedup.MatchWeights.standard());

        assertEquals(render(a), render(b),
                "the roll hash depends on this report, so it cannot depend on row order");
    }

    @Test
    @DisplayName("blocking removes almost all comparisons without losing duplicates")
    void blockingIsWorthwhile() {
        SyntheticData.Dataset data = SyntheticData.generate(20260401L, 3_600, 420, 600);
        int records = data.applications().size();
        long exhaustive = (long) records * (records - 1) / 2;

        Dedup.DedupReport report = Deduplicator.run(identities(data), Dedup.MatchWeights.standard());

        assertTrue(report.comparisons() < exhaustive / 20,
                "blocking compared " + report.comparisons() + " pairs out of " + exhaustive);
        assertTrue((double) report.duplicateApplicationCount() / data.intendedDuplicates() >= 0.80,
                "and it must not have achieved that by missing duplicates");
    }

    // ------------------------------------------------------------------ primitives

    @Test
    @DisplayName("a degenerate name cannot abort the deduplication of an entire scheme")
    void blockingSurvivesDegenerateNames() {
        // A blocking key that throws on one absurd but legal name would take down the whole
        // run, because blocking happens before any pair is scored. These are the shapes
        // that broke it: names made of digit tokens, which normalisation keeps, so the
        // spaced key is long enough while the stripped key is not.
        List<Dedup.ApplicantRecord> awkward = List.of(
                record("APP-1", Channel.PAPER_KEYED, 0, "1 2 3", "", "", "9700000001",
                        LocalDate.of(1980, 5, 5), "", "W-11"),
                record("APP-2", Channel.PAPER_KEYED, 1, "Ab 1", "", "", "9700000002",
                        LocalDate.of(1980, 5, 5), "", "W-11"),
                record("APP-3", Channel.ONLINE, 2, "Mr.", "", "", "", null, "", ""),
                record("APP-4", Channel.ONLINE, 3, "  ", "", "", "", null, null, null));

        Dedup.DedupReport report = Deduplicator.run(awkward, Dedup.MatchWeights.standard());
        assertEquals(4, report.recordCount());
        assertEquals(4, report.clusters().size(), "none of these are the same household");
    }

    @Test
    @DisplayName("name normalisation strips honorifics and ignores token order")
    void nameNormalisation() {
        assertEquals(Normalizer.nameKey("Smt. Lakshmi Devi W/O Ramesh"),
                Normalizer.nameKey("Devi Lakshmi Ramesh"));
        assertEquals("KUMAR RAMESH", Normalizer.nameKey("Ramesh Kumar"));
        assertEquals("KUMAR RAMESH", Normalizer.nameKey("Kumar, Ramesh"));
        assertEquals("", Normalizer.nameKey("Mr."));
    }

    @Test
    @DisplayName("phone normalisation drops country codes")
    void phoneNormalisation() {
        assertEquals("9876543210", Normalizer.phoneKey("+91 98765 43210"));
        assertEquals("9876543210", Normalizer.phoneKey("098765-43210"));
        assertEquals("", Normalizer.phoneKey(null));
    }

    @Test
    @DisplayName("an opaque keyed digest is a usable identifier key, a short string is not")
    void governmentIdKeyAcceptsDigests() {
        String digest = "3b2f9c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e";
        assertEquals(digest.toUpperCase(java.util.Locale.ROOT), Normalizer.governmentIdKey(digest));
        assertEquals("123456789012", Normalizer.governmentIdKey("1234 5678 9012"));
        assertEquals("", Normalizer.governmentIdKey("1234"), "too short to be a real identifier");
        assertEquals("", Normalizer.governmentIdKey("abc"), "too short to be a digest");
    }

    @Test
    @DisplayName("Jaro-Winkler tolerates the transpositions that keying actually produces")
    void jaroWinklerBehaviour() {
        assertEquals(1.0, StringMetrics.jaroWinkler("RAMESH", "RAMESH"));
        assertTrue(StringMetrics.jaroWinkler("RAMESH", "RAMHES") > 0.93,
                "a transposition must stay a strong match");
        assertTrue(StringMetrics.jaroWinkler("RAMESH", "SUNITA") < 0.55,
                "unrelated names must not");
        assertTrue(StringMetrics.jaroWinkler("", "RAMESH") == 0.0);
    }

    @Test
    @DisplayName("soundex buckets spelling variants together")
    void soundexBlocking() {
        assertEquals(Soundex.of("SHARMA"), Soundex.of("SHARMAA"));
        assertEquals(Soundex.of("SHARMA"), Soundex.of("SHARMHA"),
                "H is transparent: it must not split two identical codes");
        assertNotEquals(Soundex.of("SHARMA"), Soundex.of("IYER"));
        assertEquals("", Soundex.of(""));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Dedup.ApplicantRecord> identities(SyntheticData.Dataset data) {
        List<Dedup.ApplicantRecord> out = new ArrayList<>(data.applications().size());
        data.applications().forEach(a -> out.add(a.identity()));
        return out;
    }

    private static Dedup.ApplicantRecord record(String id, Channel channel, long minutesAfterWindow,
                                                String name, String relative, String governmentId,
                                                String phone, LocalDate dob, String address, String ward) {
        return new Dedup.ApplicantRecord(id, channel,
                WINDOW.plusSeconds(minutesAfterWindow * 60),
                name, relative, governmentId, phone, dob, address, ward);
    }

    /** A stable textual rendering of a report, for comparing two runs exactly. */
    private static String render(Dedup.DedupReport report) {
        StringBuilder sb = new StringBuilder();
        report.clusters().forEach(c -> sb.append(c.clusterId()).append('=')
                .append(String.join(",", c.memberApplicationIds())).append('\n'));
        report.autoLinked().forEach(p -> sb.append("link ").append(p.leftApplicationId())
                .append('~').append(p.rightApplicationId()).append(' ')
                .append(String.format(java.util.Locale.ROOT, "%.6f", p.score())).append('\n'));
        report.needsReview().forEach(p -> sb.append("review ").append(p.leftApplicationId())
                .append('~').append(p.rightApplicationId()).append('\n'));
        Set<String> notes = new HashSet<>(report.notes());
        sb.append("notes=").append(notes.size()).append('\n');
        return sb.toString();
    }
}
