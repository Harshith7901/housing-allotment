package dev.harshith.housing.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Value objects describing a draw: its seat plan, its seed, its per-pool rankings, and
 * the outcome recorded for every single person on the roll.
 *
 * <p>A design point worth stating explicitly: a draw does not produce 600 winners. It
 * produces an <em>outcome for all ~3,400 distinct applicants</em>, each with a ticket, a
 * rank, a pool, and a machine-generated reason. "Why not me?" is the question this system
 * exists to answer, and it cannot be answered from a list of winners.
 */
public final class Draw {

    private Draw() {
    }

    // ---------------------------------------------------------------- seat plan

    public record HorizontalMinimum(String code, int minimumSeats) {
    }

    public record VerticalSeats(String code, int seats, List<HorizontalMinimum> minima) {
        public VerticalSeats {
            minima = List.copyOf(minima == null ? List.of() : minima);
        }

        public int minimumFor(String horizontalCode) {
            return minima.stream()
                    .filter(m -> m.code().equals(horizontalCode))
                    .mapToInt(HorizontalMinimum::minimumSeats)
                    .findFirst()
                    .orElse(0);
        }
    }

    /**
     * The complete apportionment of the inventory before anyone is ranked. Deliberately a
     * first-class, storable, publishable object: "how did 600 flats become 15 here and 45
     * there" is one of the first questions an auditor asks, and the answer should not have
     * to be reverse-engineered from the result.
     *
     * @param workings human-readable arithmetic trace of the apportionment
     */
    public record SeatPlan(int totalUnits, List<VerticalSeats> verticals, List<String> workings) {
        public SeatPlan {
            verticals = List.copyOf(verticals == null ? List.of() : verticals);
            workings = List.copyOf(workings == null ? List.of() : workings);
        }

        public VerticalSeats vertical(String code) {
            return verticals.stream()
                    .filter(v -> v.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no seat plan entry for vertical " + code));
        }

        public int allocatedSeats() {
            return verticals.stream().mapToInt(VerticalSeats::seats).sum();
        }
    }

    // ---------------------------------------------------------------- seed

    /**
     * The authority's pre-draw commitment. Published together with the roll hash before
     * the public entropy value is known.
     */
    public record SeedCommitment(
            String drawId,
            String rollHash,
            String commitmentHex,
            Instant committedAt,
            String committedBy
    ) {
    }

    /**
     * The revealed seed. {@code derivation} spells out the exact string that was hashed so
     * that a reader does not have to consult the source code to reproduce it.
     */
    public record DrawSeed(
            String seedHex,
            String rollHash,
            String publicEntropy,
            String nonce,
            String derivation
    ) {
    }

    // ---------------------------------------------------------------- results

    public enum Outcome {
        SELECTED, WAITLISTED, NOT_SELECTED
    }

    public enum ReasonCode {
        SELECTED_ON_MERIT,
        SELECTED_VIA_HORIZONTAL_MINIMUM,
        SELECTED_IN_LAPSED_OPEN_POOL,
        DISPLACED_BY_HORIZONTAL_MINIMUM,
        WAITLISTED_BEHIND_SELECTED,
        NOT_SELECTED_RANK_BELOW_SEATS,
        NOT_SELECTED_NO_SEATS_IN_POOL
    }

    /** One applicant's position in one pool. */
    public record PoolCandidate(
            String applicationId,
            String ticketHex,
            int residencyTier,
            int rankInPool
    ) {
    }

    /**
     * Record of one horizontal minimum being enforced: who was promoted to satisfy it and
     * who was displaced to make room. Displacement is the most contestable single act in
     * the whole pipeline, so it is recorded by name and rank rather than inferred.
     */
    public record TopUpNote(
            String horizontalCode,
            int requiredSeats,
            int satisfiedBeforeTopUp,
            List<String> promotedApplicationIds,
            List<String> displacedApplicationIds,
            String note
    ) {
        public TopUpNote {
            promotedApplicationIds = List.copyOf(promotedApplicationIds == null ? List.of() : promotedApplicationIds);
            displacedApplicationIds = List.copyOf(displacedApplicationIds == null ? List.of() : displacedApplicationIds);
        }
    }

    public record PoolResult(
            String poolCode,
            String verticalCode,
            int seats,
            List<PoolCandidate> ranking,
            List<String> selectedApplicationIds,
            List<TopUpNote> topUps,
            int unfilledSeats
    ) {
        public PoolResult {
            ranking = List.copyOf(ranking == null ? List.of() : ranking);
            selectedApplicationIds = List.copyOf(selectedApplicationIds == null ? List.of() : selectedApplicationIds);
            topUps = List.copyOf(topUps == null ? List.of() : topUps);
        }

        public int candidateCount() {
            return ranking.size();
        }
    }

    /** The final, single outcome for one applicant, with the pool that decided it. */
    public record Selection(
            String applicationId,
            Outcome outcome,
            String decidingPoolCode,
            String ticketHex,
            int residencyTier,
            int rankInDecidingPool,
            Integer waitlistPosition,
            ReasonCode reasonCode,
            String reasonText
    ) {
    }

    public record DrawOutcome(
            String drawId,
            String rollId,
            String rollHash,
            String ruleSetVersion,
            String ruleSetHash,
            String seedHex,
            Instant executedAt,
            SeatPlan seatPlan,
            List<PoolResult> pools,
            List<Selection> selections,
            String resultHash
    ) {
        public DrawOutcome {
            pools = List.copyOf(pools == null ? List.of() : pools);
            selections = List.copyOf(selections == null ? List.of() : selections);
        }

        public long selectedCount() {
            return selections.stream().filter(s -> s.outcome() == Outcome.SELECTED).count();
        }

        public Selection forApplication(String applicationId) {
            return selections.stream()
                    .filter(s -> s.applicationId().equals(applicationId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "application " + applicationId + " was not on the roll for draw " + drawId));
        }

        public PoolResult pool(String poolCode) {
            return pools.stream()
                    .filter(p -> p.poolCode().equals(poolCode))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no such pool in draw " + drawId + ": " + poolCode));
        }
    }
}
