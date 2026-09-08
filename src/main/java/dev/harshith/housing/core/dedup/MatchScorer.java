package dev.harshith.housing.core.dedup;

import dev.harshith.housing.core.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scores one pair of applications.
 *
 * <p>Structure of the decision, in order:
 *
 * <ol>
 *   <li><b>Deterministic agreement.</b> Same government identifier, or same phone number
 *       and same date of birth. These are auto-linked without a probabilistic score,
 *       because no amount of name similarity should be able to overturn them and no
 *       amount of name dissimilarity should be able to veto them.</li>
 *   <li><b>Deterministic disagreement (a veto).</b> If both records carry a government
 *       identifier and the identifiers differ, the pair can never be auto-linked, however
 *       high the fuzzy score. It is capped at review. Two people can share a name, a
 *       birthday and an address — a father and son often do — and merging them would drop
 *       a real household from the draw.</li>
 *   <li><b>Weighted field agreement</b> for everything else, with the weight of any field
 *       missing on either side redistributed across the fields that are present. Treating
 *       a blank field as disagreement is the classic record-linkage bug: it systematically
 *       under-links paper applications, which are exactly the ones most likely to have
 *       gaps.</li>
 * </ol>
 */
public final class MatchScorer {

    private MatchScorer() {
    }

    public static Dedup.CandidatePair score(Dedup.ApplicantRecord a,
                                            Dedup.ApplicantRecord b,
                                            Dedup.MatchWeights w,
                                            List<String> sharedKeys) {
        String leftId = a.applicationId();
        String rightId = b.applicationId();

        String gidA = Normalizer.governmentIdKey(a.governmentId());
        String gidB = Normalizer.governmentIdKey(b.governmentId());
        boolean bothHaveGid = !gidA.isEmpty() && !gidB.isEmpty();

        if (bothHaveGid && gidA.equals(gidB)) {
            return new Dedup.CandidatePair(leftId, rightId,
                    Dedup.MatchMethod.DETERMINISTIC_GOVERNMENT_ID, 1.0, Dedup.MatchDecision.AUTO_LINKED,
                    List.of(new Dedup.FeatureScore("governmentId", 1.0, 1.0, true, "identifiers are identical")),
                    "Both applications carry the same government identifier.",
                    sharedKeys);
        }

        String phoneA = Normalizer.phoneKey(a.phone());
        String phoneB = Normalizer.phoneKey(b.phone());
        boolean phoneUsable = phoneA.length() == 10 && phoneA.equals(phoneB);
        boolean dobEqual = a.dateOfBirth() != null && a.dateOfBirth().equals(b.dateOfBirth());

        if (!bothHaveGid && phoneUsable && dobEqual) {
            return new Dedup.CandidatePair(leftId, rightId,
                    Dedup.MatchMethod.DETERMINISTIC_PHONE_AND_DOB, 0.99, Dedup.MatchDecision.AUTO_LINKED,
                    List.of(new Dedup.FeatureScore("phone", 0.5, 1.0, true, "same ten-digit number"),
                            new Dedup.FeatureScore("dateOfBirth", 0.5, 1.0, true, "identical")),
                    "Same phone number and same date of birth, with no conflicting government identifier.",
                    sharedKeys);
        }

        List<Dedup.FeatureScore> features = new ArrayList<>();

        String nameA = Normalizer.nameKey(a.fullName());
        String nameB = Normalizer.nameKey(b.fullName());
        double nameScore = StringMetrics.jaroWinkler(nameA, nameB);
        features.add(new Dedup.FeatureScore("name", w.name(), nameScore,
                !nameA.isEmpty() && !nameB.isEmpty(),
                "jaro-winkler(\"" + nameA + "\", \"" + nameB + "\")"));

        String relA = Normalizer.nameKey(a.relativeName());
        String relB = Normalizer.nameKey(b.relativeName());
        features.add(new Dedup.FeatureScore("relativeName", w.relative(),
                StringMetrics.jaroWinkler(relA, relB),
                !relA.isEmpty() && !relB.isEmpty(),
                "jaro-winkler(\"" + relA + "\", \"" + relB + "\")"));

        features.add(dateOfBirthFeature(a.dateOfBirth(), b.dateOfBirth(), w.dateOfBirth()));
        features.add(phoneFeature(phoneA, phoneB, w.phone()));

        Set<String> addrA = Normalizer.addressTokens(a.addressLine());
        Set<String> addrB = Normalizer.addressTokens(b.addressLine());
        features.add(new Dedup.FeatureScore("address", w.address(),
                StringMetrics.jaccard(addrA, addrB),
                !addrA.isEmpty() && !addrB.isEmpty(),
                "token jaccard over " + addrA.size() + " and " + addrB.size() + " tokens"));

        boolean wardAvailable = !Text.isBlank(a.wardCode()) && !Text.isBlank(b.wardCode());
        features.add(new Dedup.FeatureScore("ward", w.ward(),
                wardAvailable && Text.upper(a.wardCode().trim()).equals(Text.upper(b.wardCode().trim())) ? 1.0 : 0.0,
                wardAvailable,
                wardAvailable ? a.wardCode() + " vs " + b.wardCode() : "missing on one side"));

        double weighted = 0.0;
        double availableWeight = 0.0;
        for (Dedup.FeatureScore f : features) {
            if (!f.available()) {
                continue;
            }
            weighted += f.weight() * f.score();
            availableWeight += f.weight();
        }
        double score = availableWeight == 0.0 ? 0.0 : weighted / availableWeight;

        Dedup.MatchDecision decision;
        if (score >= w.autoLinkThreshold()) {
            decision = Dedup.MatchDecision.AUTO_LINKED;
        } else if (score >= w.reviewThreshold()) {
            decision = Dedup.MatchDecision.NEEDS_REVIEW;
        } else {
            decision = Dedup.MatchDecision.DISTINCT;
        }

        StringBuilder rationale = new StringBuilder();
        rationale.append("Weighted field agreement ")
                 .append(String.format(java.util.Locale.ROOT, "%.4f", score))
                 .append(" against thresholds auto=").append(w.autoLinkThreshold())
                 .append(", review=").append(w.reviewThreshold()).append(". ");

        if (bothHaveGid && decision == Dedup.MatchDecision.AUTO_LINKED) {
            decision = Dedup.MatchDecision.NEEDS_REVIEW;
            rationale.append("Auto-link vetoed: both applications carry a government identifier and the two "
                    + "identifiers differ, so this pair must be decided by a reviewer.");
        } else if (bothHaveGid) {
            rationale.append("Note: the two government identifiers differ.");
        } else {
            rationale.append("At least one application has no government identifier on record.");
        }

        return new Dedup.CandidatePair(leftId, rightId, Dedup.MatchMethod.PROBABILISTIC,
                score, decision, features, rationale.toString(), sharedKeys);
    }

    private static Dedup.FeatureScore dateOfBirthFeature(LocalDate a, LocalDate b, double weight) {
        boolean available = a != null && b != null;
        if (!available) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 0.0, false, "missing on one side");
        }
        if (a.equals(b)) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 1.0, true, "identical");
        }
        // Day/month transposition is the single most common date error on keyed forms.
        if (a.getYear() == b.getYear()
                && a.getDayOfMonth() == b.getMonthValue()
                && a.getMonthValue() == b.getDayOfMonth()) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 0.85, true,
                    "day and month transposed (" + a + " vs " + b + ")");
        }
        if (a.getYear() == b.getYear() && a.getMonthValue() == b.getMonthValue()) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 0.60, true, "same year and month");
        }
        if (a.getYear() == b.getYear()) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 0.30, true, "same year only");
        }
        if (Math.abs(a.getYear() - b.getYear()) == 1) {
            return new Dedup.FeatureScore("dateOfBirth", weight, 0.15, true, "adjacent years");
        }
        return new Dedup.FeatureScore("dateOfBirth", weight, 0.0, true, a + " vs " + b);
    }

    private static Dedup.FeatureScore phoneFeature(String a, String b, double weight) {
        boolean available = !a.isEmpty() && !b.isEmpty();
        if (!available) {
            return new Dedup.FeatureScore("phone", weight, 0.0, false, "missing on one side");
        }
        if (a.equals(b)) {
            return new Dedup.FeatureScore("phone", weight, 1.0, true, "identical");
        }
        int distance = StringMetrics.levenshtein(a, b);
        double score = switch (distance) {
            case 1 -> 0.70;
            case 2 -> 0.35;
            default -> 0.0;
        };
        return new Dedup.FeatureScore("phone", weight, score, true, "edit distance " + distance);
    }
}
