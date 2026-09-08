package dev.harshith.housing.persistence;

/** A reviewer's verdict on a queued duplicate match. */
public enum ReviewDecision {
    /** Not yet decided. Blocks the roll freeze. */
    PENDING,
    /** The same household. The later application is superseded. */
    SAME_HOUSEHOLD,
    /** Different households that happen to look alike. Both keep their tickets. */
    DIFFERENT_HOUSEHOLDS
}
