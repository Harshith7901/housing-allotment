package dev.harshith.housing.persistence;

import java.util.List;

/**
 * The scheme's lifecycle, enforced as a state machine.
 *
 * <p>Most of this system's integrity comes from this enum rather than from any clever
 * cryptography. The hash chain proves that a record was not edited; the phase machine is
 * what stops the edit being <em>accepted</em> in the first place. An application cannot be
 * corrected after the roll is frozen. A seed cannot be committed before the roll exists. A
 * draw cannot run before the seed is committed or twice afterwards. Each of those is a
 * real-world failure mode of allotment schemes, and each is a single illegal transition
 * here.
 *
 * <p>The transitions are deliberately one-way. There is no path back from
 * {@link #ROLL_FROZEN} to {@link #VERIFICATION}: a late correction does not reopen a
 * frozen roll, it produces a new roll, a new commitment and a separately authorised draw,
 * with both rolls permanently on record.
 */
public enum SchemePhase {
    INTAKE_OPEN,
    INTAKE_CLOSED,
    DEDUPLICATION,
    VERIFICATION,
    ROLL_FROZEN,
    SEED_COMMITTED,
    DRAW_EXECUTED,
    RESULT_PUBLISHED,
    ALLOTMENT,
    FINALISED;

    public List<SchemePhase> allowedNext() {
        return switch (this) {
            case INTAKE_OPEN -> List.of(INTAKE_CLOSED);
            case INTAKE_CLOSED -> List.of(DEDUPLICATION);
            case DEDUPLICATION -> List.of(VERIFICATION);
            case VERIFICATION -> List.of(ROLL_FROZEN);
            case ROLL_FROZEN -> List.of(SEED_COMMITTED);
            case SEED_COMMITTED -> List.of(DRAW_EXECUTED);
            case DRAW_EXECUTED -> List.of(RESULT_PUBLISHED);
            case RESULT_PUBLISHED -> List.of(ALLOTMENT);
            case ALLOTMENT -> List.of(FINALISED);
            case FINALISED -> List.of();
        };
    }

    public boolean canAdvanceTo(SchemePhase next) {
        return allowedNext().contains(next);
    }

    /** True once the roll is frozen, after which applications become read-only. */
    public boolean isFrozen() {
        return ordinal() >= ROLL_FROZEN.ordinal();
    }
}
