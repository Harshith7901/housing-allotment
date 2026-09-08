package dev.harshith.housing.web.dto;

import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.ReviewDecision;
import dev.harshith.housing.persistence.SchemePhase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Request bodies, grouped because they are one cohesive set of thin transport records with
 * no behaviour beyond validation and one mapping method.
 */
public final class Requests {

    private Requests() {
    }

    public record CreateScheme(@NotBlank @Size(max = 32) String schemeCode,
                               @NotBlank @Size(max = 200) String name) {
    }

    public record AdvancePhase(@NotNull SchemePhase to) {
    }

    /**
     * The delimiter and precision constraints are not cosmetic. The published rule set is a
     * line- and pipe-delimited document whose bytes are hashed, so a {@code |} in a label
     * or a fifth decimal place cannot be represented — and because rule sets are immutable
     * and the scheme points at the active version, accepting one would fail on the next
     * read with no way to repair it. They are refused here, as a 400.
     */
    public record Quota(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Z0-9_]+", message = "must be upper-case letters, digits and underscores")
            String code,

            @NotBlank @Size(max = 200)
            @Pattern(regexp = "[^|\\r\\n]+", message = "must not contain a pipe or a line break")
            String label,

            @NotNull @PositiveOrZero
            @Digits(integer = 3, fraction = 4, message = "may carry at most 4 decimal places")
            BigDecimal percent
    ) {
    }

    /**
     * A rule set to publish.
     *
     * <p>{@code totalUnits} is validated against the actual available inventory at roll
     * freeze and the freeze is refused if they differ, so this is not a figure that can
     * quietly drift from reality.
     */
    public record PublishRuleSet(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[^|\\r\\n]+", message = "must not contain a pipe or a line break")
            String version,

            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[^|\\r\\n]+", message = "must not contain a pipe or a line break")
            String schemeCode,

            @Positive int totalUnits,

            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Z0-9_]+", message = "must be upper-case letters, digits and underscores")
            String openCode,
            @Valid List<Quota> reservedQuotas,
            @Valid List<Quota> horizontalQuotas,
            @NotNull Rules.ResidencyMode residencyMode,
            @PositiveOrZero int residencyMinYears,
            @NotNull Rules.LapsePolicy lapsePolicy,
            @PositiveOrZero int waitlistSize,

            @Size(max = 500)
            @Pattern(regexp = "[^|\\r\\n]*", message = "must not contain a pipe or a line break")
            String publishedRulesUri
    ) {
        public Rules.RuleSet toRuleSet() {
            List<Rules.ReservedQuota> reserved = new ArrayList<>();
            if (reservedQuotas != null) {
                reservedQuotas.forEach(q ->
                        reserved.add(new Rules.ReservedQuota(q.code(), q.label(), q.percent())));
            }
            List<Rules.HorizontalQuota> horizontal = new ArrayList<>();
            if (horizontalQuotas != null) {
                horizontalQuotas.forEach(q ->
                        horizontal.add(new Rules.HorizontalQuota(q.code(), q.label(), q.percent())));
            }
            return new Rules.RuleSet(version, schemeCode, totalUnits, openCode, reserved, horizontal,
                    new Rules.ResidencyRule(residencyMode, residencyMinYears),
                    lapsePolicy, waitlistSize,
                    publishedRulesUri == null ? "" : publishedRulesUri);
        }
    }

    public record Correction(@Valid @NotNull IntakeRequest application,
                             @NotBlank @Size(max = 500) String reason) {
    }

    public record RecordEligibility(@NotBlank @Size(max = 64) String checkCode,
                                    boolean passed,
                                    @Size(max = 1000) String reason,
                                    @Size(max = 300) String evidenceRef) {
    }

    public record ReviewDuplicate(@NotNull ReviewDecision decision,
                                  @Size(max = 1000) String note) {
    }

    public record FreezeRoll(@NotBlank @Size(max = 64) String rollId) {
    }

    /**
     * @param entropySourceDescription names, in words and in advance, the public value that
     *                                 will seed the draw. Required at commit time and
     *                                 stored, so it cannot be chosen afterwards to suit an
     *                                 outcome.
     */
    public record CommitSeed(@NotBlank @Size(max = 64) String drawId,
                             @NotBlank @Size(max = 300) String entropySourceDescription) {
    }

    public record ExecuteDraw(@NotBlank @Size(max = 300) String publicEntropy) {
    }

    public record Reason(@NotBlank @Size(max = 1000) String reason) {
    }

    public record Unit(@NotBlank @Size(max = 32) String unitId,
                       @Size(max = 32) String block,
                       @NotBlank @Size(max = 32) String unitType,
                       @PositiveOrZero int floor) {
    }

    public record AddUnits(@NotEmpty @Valid List<Unit> units) {
    }
}
