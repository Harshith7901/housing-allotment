package dev.harshith.housing.core;

import dev.harshith.housing.core.audit.Audit;
import dev.harshith.housing.core.audit.HashChain;
import dev.harshith.housing.core.bundle.VerificationBundle;
import dev.harshith.housing.core.draw.AllocationEngine;
import dev.harshith.housing.core.draw.ResultHasher;
import dev.harshith.housing.core.draw.RollHasher;
import dev.harshith.housing.core.draw.SeedDeriver;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.core.model.RulesCodec;
import dev.harshith.housing.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commitment protocol, the hash chain, the canonical encodings, and the published
 * bundle: everything whose job is to make a lie detectable.
 */
class IntegrityTest {

    private static final Instant T0 = Instant.parse("2026-04-01T04:00:00Z");
    private static final String NONCE = "b3c1d5e7f9a2b4c6d8e0f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d3e5f7a9b1c3";

    // ------------------------------------------------------------------ commit-reveal

    @Test
    @DisplayName("the published commitment is the hash of the nonce revealed later")
    void commitmentBindsTheNonce() {
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", "roll-hash", NONCE, T0, "registrar@scheme");

        assertTrue(SeedDeriver.commitmentHolds(commitment, NONCE));
        assertEquals(Hashing.sha256Hex("commit/1|" + NONCE), commitment.commitmentHex(),
                "the commitment must be reproducible by anybody with the revealed nonce");
    }

    @Test
    @DisplayName("substituting the nonce after committing is detected and refused")
    void substitutedNonceIsRefused() {
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", "roll-hash", NONCE, T0, "registrar@scheme");
        String swapped = NONCE.replace('b', 'a');

        assertFalse(SeedDeriver.commitmentHolds(commitment, swapped));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeedDeriver.reveal(commitment, "ENTROPY-1", swapped));
        assertTrue(failure.getMessage().contains("must not proceed"), failure.getMessage());
    }

    @Test
    @DisplayName("the draw cannot proceed without a public entropy value")
    void entropyIsRequired() {
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", "roll-hash", NONCE, T0, "registrar@scheme");
        assertThrows(IllegalArgumentException.class, () -> SeedDeriver.reveal(commitment, "  ", NONCE));
    }

    @Test
    @DisplayName("a third party can recompute the seed from the published values alone")
    void seedIsIndependentlyRecomputable() {
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", "the-roll-hash", NONCE, T0, "registrar@scheme");
        Draw.DrawSeed seed = SeedDeriver.reveal(commitment, "LOTTERY:481902", NONCE);

        assertEquals(seed.seedHex(),
                SeedDeriver.recompute("the-roll-hash", "LOTTERY:481902", NONCE));
        assertEquals(seed.seedHex(),
                Hashing.sha256Hex("seed/1|the-roll-hash|LOTTERY:481902|" + NONCE),
                "the derivation printed in the bundle must be the derivation actually used");
    }

    @Test
    @DisplayName("the seed changes if the roll changes, so a seed cannot outlive its roll")
    void seedIsBoundToTheRoll() {
        assertNotEquals(
                SeedDeriver.recompute("roll-a", "LOTTERY:1", NONCE),
                SeedDeriver.recompute("roll-b", "LOTTERY:1", NONCE));
    }

    // ------------------------------------------------------------------ audit chain

    @Test
    @DisplayName("an untouched chain verifies from the genesis hash")
    void intactChainVerifies() {
        List<Audit.AuditEvent> log = chain(12);
        Audit.ChainVerification result = HashChain.verify(log);

        assertTrue(result.intact());
        assertEquals(12, result.eventsChecked());
        assertEquals(HashChain.head(log), log.get(11).hash());
        assertEquals(Hashing.GENESIS, log.get(0).previousHash());
    }

    @Test
    @DisplayName("an empty chain verifies, and says so rather than pretending to be evidence")
    void emptyChain() {
        Audit.ChainVerification result = HashChain.verify(List.of());
        assertTrue(result.intact());
        assertEquals(0, result.eventsChecked());
        assertTrue(result.detail().contains("empty"));
    }

    @Test
    @DisplayName("editing one historical event is detected, and localised to that event")
    void editedEventIsDetected() {
        List<Audit.AuditEvent> log = new ArrayList<>(chain(10));
        Audit.AuditEvent victim = log.get(4);
        log.set(4, new Audit.AuditEvent(victim.sequence(), victim.eventId(), victim.occurredAt(),
                victim.actor(), victim.actorRole(), victim.action(), victim.entityType(),
                victim.entityId(), "selected=601", victim.previousHash(), victim.hash()));

        Audit.ChainVerification result = HashChain.verify(log);

        assertFalse(result.intact());
        assertEquals(victim.sequence(), result.firstBrokenSequence());
        assertTrue(result.detail().contains("has been altered"), result.detail());
    }

    @Test
    @DisplayName("deleting an event is detected as a gap in the sequence")
    void deletedEventIsDetected() {
        List<Audit.AuditEvent> log = new ArrayList<>(chain(10));
        log.remove(3);

        Audit.ChainVerification result = HashChain.verify(log);

        assertFalse(result.intact());
        assertTrue(result.detail().contains("deleted") || result.detail().contains("sequence"),
                result.detail());
    }

    @Test
    @DisplayName("reordering events is detected")
    void reorderedEventsAreDetected() {
        List<Audit.AuditEvent> log = new ArrayList<>(chain(10));
        Audit.AuditEvent a = log.get(5);
        log.set(5, log.get(6));
        log.set(6, a);

        assertFalse(HashChain.verify(log).intact());
    }

    @Test
    @DisplayName("re-hashing an edited event to hide it still breaks the following link")
    void rewritingOneEventDoesNotRepairTheChain() {
        List<Audit.AuditEvent> log = new ArrayList<>(chain(10));
        Audit.AuditEvent victim = log.get(4);

        // A determined operator edits the payload and recomputes that event's own hash, so
        // the event is internally consistent. The next event still records the old hash as
        // its predecessor, so the chain breaks one link later. Concealing it requires
        // rewriting every subsequent row.
        Audit.AuditEvent forged = HashChain.link(victim.sequence(), victim.eventId(),
                victim.occurredAt(), victim.actor(), victim.actorRole(), victim.action(),
                victim.entityType(), victim.entityId(), "selected=601", victim.previousHash());
        log.set(4, forged);

        Audit.ChainVerification result = HashChain.verify(log);
        assertFalse(result.intact());
        assertEquals(victim.sequence() + 1, result.firstBrokenSequence());
    }

    @Test
    @DisplayName("timestamps are truncated to whole seconds so storage precision cannot break a chain")
    void subSecondPrecisionIsIrrelevant() {
        Audit.AuditEvent precise = HashChain.link(1, "EVT-1",
                Instant.parse("2026-04-01T04:00:00.123456789Z"), "a", "SCHEME_ADMIN",
                "ROLL_FROZEN", "ROLL", "R-1", "entries=3419", null);
        Audit.AuditEvent coarse = HashChain.link(1, "EVT-1",
                Instant.parse("2026-04-01T04:00:00Z"), "a", "SCHEME_ADMIN",
                "ROLL_FROZEN", "ROLL", "R-1", "entries=3419", null);

        assertEquals(coarse.hash(), precise.hash());
    }

    // ------------------------------------------------------------------ encodings

    @Test
    @DisplayName("a rule set survives an encode/decode round trip with the same hash")
    void ruleSetRoundTrip() {
        Rules.RuleSet original = RuleFixtures.standard(600);
        String encoded = RulesCodec.encode(original);
        Rules.RuleSet decoded = RulesCodec.decode(encoded);

        assertEquals(RulesCodec.hash(original), RulesCodec.hash(decoded));
        assertEquals(encoded, RulesCodec.encode(decoded));
        assertEquals(original.totalUnits(), decoded.totalUnits());
        assertEquals(original.reservedQuotas().size(), decoded.reservedQuotas().size());
        assertEquals(original.residency(), decoded.residency());
        assertEquals(original.waitlistSize(), decoded.waitlistSize());
    }

    @Test
    @DisplayName("the encoded rule set is human-readable, because a hash of unreadable bytes proves nothing")
    void ruleSetEncodingIsReadable() {
        String encoded = RulesCodec.encode(RuleFixtures.standard(600));

        assertTrue(encoded.contains("totalUnits=600"));
        assertTrue(encoded.contains("reserved=CAT_B|Reserved category B|7.5000"));
        assertTrue(encoded.contains("residency=PRIORITY_TIER|3"));
        assertTrue(encoded.contains("lapse=LAPSE_TO_OPEN"));
    }

    @Test
    @DisplayName("a label containing the field delimiter is refused when the rule set is built")
    void delimitersAreRefusedAtConstruction() {
        // Otherwise the rule set encodes happily and fails to *decode* later. Rule sets are
        // immutable and the scheme points at the active version, so that failure would
        // arrive on the next read and leave the scheme with no repair path.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Rules.ReservedQuota("CAT_A", "Category A|B", new java.math.BigDecimal("15")));
        assertTrue(failure.getMessage().contains("pipe"), failure.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new Rules.RuleSet(
                "V1", "SCH", 10, "OPEN", List.of(), List.of(),
                Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 0,
                "https://example.gov/rules.pdf\nlapse=CARRY_FORWARD"));
    }

    @Test
    @DisplayName("a percentage too precise to record exactly is refused, never silently rounded")
    void overPrecisePercentagesAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Rules.HorizontalQuota("WOMEN", "Women", new java.math.BigDecimal("30.000001")));
        // Four decimals is the recorded precision and must be accepted.
        assertEquals("7.5000", dev.harshith.housing.core.util.Text.percent(
                new java.math.BigDecimal("7.5")));
    }

    @Test
    @DisplayName("an unknown directive in a rule set is refused rather than ignored")
    void unknownRuleSetDirectiveIsRefused() {
        String tampered = RulesCodec.encode(RuleFixtures.standard(600)) + "secretBonus=CAT_A|10\n";
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> RulesCodec.decode(tampered));
        assertTrue(failure.getMessage().contains("secretBonus"), failure.getMessage());
    }

    @Test
    @DisplayName("the roll hash is the SHA-256 of the published roll text, and nothing else")
    void rollHashIsTheHashOfThePublishedText() {
        Roll.DrawRoll roll = smallRoll();

        assertTrue(RollHasher.verify(roll));
        Roll.DrawRoll withoutHash = new Roll.DrawRoll(roll.rollId(), roll.schemeCode(),
                roll.ruleSetVersion(), roll.ruleSetHash(), roll.frozenAt(), roll.entries(), "");
        assertEquals(roll.rollHash(), Hashing.sha256Hex(RollHasher.encode(withoutHash)),
                "a reader must be able to sha256sum the file they were given");
    }

    @Test
    @DisplayName("the roll hash does not depend on the order entries arrived in")
    void rollHashIsOrderIndependent() {
        List<Roll.RollEntry> entries = smallRoll().entries();
        List<Roll.RollEntry> shuffled = new ArrayList<>(entries);
        java.util.Collections.reverse(shuffled);

        Roll.DrawRoll a = RollHasher.freeze("R", "SCH", "V", "RH", T0, entries);
        Roll.DrawRoll b = RollHasher.freeze("R", "SCH", "V", "RH", T0, shuffled);

        assertEquals(a.rollHash(), b.rollHash());
    }

    @Test
    @DisplayName("two applications cannot appear twice on one roll")
    void duplicateRollEntriesAreRefused() {
        List<Roll.RollEntry> entries = List.of(
                new Roll.RollEntry("APP-1", "CL-1", "OPEN", Set.of(), 0, List.of()),
                new Roll.RollEntry("APP-1", "CL-1", "OPEN", Set.of(), 0, List.of()));
        assertThrows(IllegalStateException.class,
                () -> RollHasher.freeze("R", "SCH", "V", "RH", T0, entries));
    }

    @Test
    @DisplayName("the roll text carries no name, phone number, address or identifier")
    void rollCarriesNoIdentifyingData() {
        String text = RollHasher.encode(smallRoll());

        // The roll is designed to be published in full, which is only safe because it
        // holds the attributes the rules act on and nothing else.
        assertFalse(text.contains("RAMESH"));
        assertFalse(text.contains("9876543210"));
        assertTrue(text.contains("APP-0001"));
        assertTrue(text.contains("CAT_A"));
    }

    @Test
    @DisplayName("the result hash covers the decision, not the wording of the explanations")
    void resultHashIgnoresPresentation() {
        Draw.DrawOutcome outcome = drawSmall();

        List<Draw.Selection> reworded = new ArrayList<>();
        for (Draw.Selection s : outcome.selections()) {
            reworded.add(new Draw.Selection(s.applicationId(), s.outcome(), s.decidingPoolCode(),
                    s.ticketHex(), s.residencyTier(), s.rankInDecidingPool(), s.waitlistPosition(),
                    s.reasonCode(), "Rewritten in plainer English for the 2027 notification."));
        }
        Draw.DrawOutcome republished = new Draw.DrawOutcome(outcome.drawId(), outcome.rollId(),
                outcome.rollHash(), outcome.ruleSetVersion(), outcome.ruleSetHash(),
                outcome.seedHex(), outcome.executedAt(), outcome.seatPlan(), outcome.pools(),
                reworded, "");

        assertEquals(outcome.resultHash(), ResultHasher.hash(republished),
                "improving an explanation must not invalidate an announced result");
    }

    @Test
    @DisplayName("changing any decision in the result changes its hash")
    void resultHashCoversEveryDecision() {
        Draw.DrawOutcome outcome = drawSmall();

        List<Draw.Selection> forged = new ArrayList<>(outcome.selections());
        Draw.Selection loser = forged.stream()
                .filter(s -> s.outcome() != Draw.Outcome.SELECTED)
                .findFirst()
                .orElseThrow();
        forged.set(forged.indexOf(loser), new Draw.Selection(loser.applicationId(),
                Draw.Outcome.SELECTED, loser.decidingPoolCode(), loser.ticketHex(),
                loser.residencyTier(), loser.rankInDecidingPool(), null,
                Draw.ReasonCode.SELECTED_ON_MERIT, loser.reasonText()));

        Draw.DrawOutcome tampered = new Draw.DrawOutcome(outcome.drawId(), outcome.rollId(),
                outcome.rollHash(), outcome.ruleSetVersion(), outcome.ruleSetHash(),
                outcome.seedHex(), outcome.executedAt(), outcome.seatPlan(), outcome.pools(),
                forged, outcome.resultHash());

        assertNotEquals(outcome.resultHash(), ResultHasher.hash(tampered));
        assertFalse(ResultHasher.verify(tampered));
    }

    // ------------------------------------------------------------------ bundle

    @Test
    @DisplayName("the bundle's manifest hashes match the files it ships")
    void bundleIsInternallyConsistent() {
        Rules.RuleSet rules = RuleFixtures.standard(10);
        Roll.DrawRoll roll = smallRoll();
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", roll.rollHash(), NONCE, T0, "registrar@scheme");
        Draw.DrawSeed seed = SeedDeriver.reveal(commitment, "LOTTERY:481902", NONCE);
        Draw.DrawOutcome outcome = AllocationEngine.execute("DRAW-1", roll, rules, seed, T0);

        VerificationBundle.Contents bundle =
                VerificationBundle.build(rules, roll, commitment, seed, outcome, "audit-head");

        assertEquals(Set.of("rules.txt", "roll.txt", "seed.txt", "result.txt"), bundle.files().keySet());

        // Each published hash must be exactly the digest of the file that carries it, so
        // that sha256sum on its own is a sufficient check.
        assertTrue(bundle.manifest().contains(
                "ruleSetHash=" + Hashing.sha256Hex(bundle.files().get("rules.txt"))));
        assertTrue(bundle.manifest().contains(
                "rollHash=" + Hashing.sha256Hex(bundle.files().get("roll.txt"))));
        assertTrue(bundle.manifest().contains(
                "resultHash=" + Hashing.sha256Hex(bundle.files().get("result.txt"))));
        assertTrue(bundle.manifest().contains("auditHeadHash=audit-head"));

        // And the roll in the bundle must be the roll the rules in the bundle applied to.
        assertTrue(bundle.files().get("roll.txt")
                .contains("ruleSetHash=" + RulesCodec.hash(rules)));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Audit.AuditEvent> chain(int length) {
        List<Audit.AuditEvent> log = new ArrayList<>(length);
        String previous = null;
        for (int i = 1; i <= length; i++) {
            Audit.AuditEvent event = HashChain.link(i, "EVT-" + i, T0.plusSeconds(i * 3600L),
                    "officer" + (i % 3) + "@scheme", "SCHEME_ADMIN", "ACTION_" + i,
                    "SCHEME", "SCH-TEST", "step=" + i, previous);
            log.add(event);
            previous = event.hash();
        }
        return log;
    }

    private static Roll.DrawRoll smallRoll() {
        List<Roll.RollEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            entries.add(new Roll.RollEntry(String.format("APP-%04d", i), "CL-" + i,
                    i % 4 == 0 ? "CAT_A" : "OPEN",
                    i % 3 == 0 ? Set.of("WOMEN") : Set.of(),
                    i % 5, List.of("TWO_BHK", "ONE_BHK")));
        }
        return RollHasher.freeze("ROLL-1", "SCH-TEST", RuleFixtures.standard(10).version(),
                RulesCodec.hash(RuleFixtures.standard(10)), T0, entries);
    }

    private static Draw.DrawOutcome drawSmall() {
        Rules.RuleSet rules = RuleFixtures.standard(10);
        Roll.DrawRoll roll = smallRoll();
        Draw.SeedCommitment commitment =
                SeedDeriver.commit("DRAW-1", roll.rollHash(), NONCE, T0, "registrar@scheme");
        return AllocationEngine.execute("DRAW-1", roll, rules,
                SeedDeriver.reveal(commitment, "LOTTERY:481902", NONCE), T0);
    }
}
