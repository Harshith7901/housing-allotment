package dev.harshith.housing.core.dedup;

import dev.harshith.housing.core.model.Channel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Value objects for duplicate detection.
 *
 * <p>The governing principle of this whole package: <b>a duplicate is never deleted and
 * never silently rejected.</b> Roughly a tenth of 4,000 applications are the same family
 * applying twice because they were not sure the first one went through, and the cost of
 * the two possible mistakes is wildly asymmetric. Leaving a duplicate in gives one family
 * two tickets — unfair, and detectable later. Wrongly merging two records removes a real
 * family from the draw entirely, and they will find out only when the list is published.
 *
 * <p>So the matcher does three things and no more: it links what is certain, it queues
 * what is plausible for a human, and it records the arithmetic for both. Merging is a
 * recorded decision with an author, not an inference.
 */
public final class Dedup {

    private Dedup() {
    }

    /**
     * The identity-bearing view of an application, used only for matching.
     *
     * <p>{@code governmentId} is expected to be a salted hash in production rather than the
     * number itself; normalisation and comparison are unaffected, and nothing downstream
     * of this package ever sees it.
     */
    public record ApplicantRecord(
            String applicationId,
            Channel channel,
            Instant submittedAt,
            String fullName,
            String relativeName,
            String governmentId,
            String phone,
            LocalDate dateOfBirth,
            String addressLine,
            String wardCode
    ) {
    }

    public enum MatchDecision {
        /** Certain enough to merge without asking anybody. */
        AUTO_LINKED,
        /** Plausible. Goes to a human reviewer and blocks the roll freeze until decided. */
        NEEDS_REVIEW,
        /** Not the same person. */
        DISTINCT
    }

    public enum MatchMethod {
        DETERMINISTIC_GOVERNMENT_ID,
        DETERMINISTIC_PHONE_AND_DOB,
        PROBABILISTIC
    }

    /**
     * One field's contribution to a match score.
     *
     * @param available false when the field is missing on either side, in which case its
     *                  weight is redistributed across the fields that are present rather
     *                  than counted as evidence of difference
     */
    public record FeatureScore(String feature, double weight, double score, boolean available, String detail) {
    }

    public record CandidatePair(
            String leftApplicationId,
            String rightApplicationId,
            MatchMethod method,
            double score,
            MatchDecision decision,
            List<FeatureScore> features,
            String rationale,
            List<String> sharedBlockingKeys
    ) {
        public CandidatePair {
            features = List.copyOf(features == null ? List.of() : features);
            sharedBlockingKeys = List.copyOf(sharedBlockingKeys == null ? List.of() : sharedBlockingKeys);
        }
    }

    /**
     * Field weights and the two thresholds.
     *
     * <p>These are configuration, not constants, and they belong in the scheme's published
     * procedure document. A reviewer is entitled to know that 0.93 was the auto-link bar
     * before the draw, not after.
     */
    public record MatchWeights(
            double name,
            double relative,
            double dateOfBirth,
            double phone,
            double address,
            double ward,
            double autoLinkThreshold,
            double reviewThreshold
    ) {
        public static MatchWeights standard() {
            return new MatchWeights(0.32, 0.10, 0.22, 0.18, 0.12, 0.06, 0.93, 0.80);
        }

        public double totalWeight() {
            return name + relative + dateOfBirth + phone + address + ward;
        }
    }

    /**
     * A set of applications treated as one household for the draw.
     *
     * @param primaryApplicationId the one application that carries the cluster's single
     *                             lottery ticket; the earliest submission wins, because
     *                             "the first one went through" is what the applicant
     *                             believed when they applied again
     */
    public record Cluster(
            String clusterId,
            String primaryApplicationId,
            List<String> memberApplicationIds,
            String basis
    ) {
        public Cluster {
            memberApplicationIds = List.copyOf(memberApplicationIds == null ? List.of() : memberApplicationIds);
        }

        public boolean isSingleton() {
            return memberApplicationIds.size() <= 1;
        }
    }

    public record DedupReport(
            List<Cluster> clusters,
            List<CandidatePair> autoLinked,
            List<CandidatePair> needsReview,
            int recordCount,
            int comparisons,
            List<String> notes
    ) {
        public DedupReport {
            clusters = List.copyOf(clusters == null ? List.of() : clusters);
            autoLinked = List.copyOf(autoLinked == null ? List.of() : autoLinked);
            needsReview = List.copyOf(needsReview == null ? List.of() : needsReview);
            notes = List.copyOf(notes == null ? List.of() : notes);
        }

        public long duplicateApplicationCount() {
            return clusters.stream().mapToLong(c -> c.memberApplicationIds().size() - 1L).sum();
        }

        public int distinctHouseholds() {
            return clusters.size();
        }
    }
}
