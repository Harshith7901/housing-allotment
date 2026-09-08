package dev.harshith.housing.persistence;

/**
 * The status of an application <em>as an application</em>.
 *
 * <p>Note what is not here: SELECTED, WAITLISTED, ALLOTTED. Draw outcomes live on the
 * draw's own selection records and allotments on the allotment records, keyed by draw.
 * Keeping them off the application row is deliberate — a scheme can hold more than one
 * draw against the same application set (a lapsed-seat draw, a re-draw ordered on appeal),
 * and a mutable "current outcome" column on the application would make the outcome of the
 * <em>first</em> draw unrecoverable the moment a second one ran.
 */
public enum ApplicationStatus {
    /** Keyed in and acknowledged. */
    RECEIVED,
    /** A plausible duplicate match is awaiting a reviewer's decision. */
    PENDING_DUPLICATE_REVIEW,
    /** Confirmed as another application from the same household; carries no ticket. */
    SUPERSEDED,
    /** Failed a published eligibility condition; excluded from the roll with a reason. */
    INELIGIBLE,
    /** Verified and eligible; will appear on the roll when it is frozen. */
    ELIGIBLE
}
