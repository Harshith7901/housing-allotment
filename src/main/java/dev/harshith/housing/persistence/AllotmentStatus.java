package dev.harshith.housing.persistence;

/** The state of one offered flat. */
public enum AllotmentStatus {
    OFFERED,
    ACCEPTED,
    /** Declined, or lapsed on a missed deadline, or failed final verification. */
    FORFEITED,
    /** Offered to this applicant as a waitlist promotion after somebody forfeited. */
    OFFERED_ON_PROMOTION
}
