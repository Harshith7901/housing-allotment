package dev.harshith.housing.core.model;

/**
 * How an application entered the system.
 *
 * <p>This is not bookkeeping trivia. Paper applications are keyed in by a clerk from
 * handwriting, so they carry a materially higher rate of transcription error in exactly
 * the fields used for duplicate detection: names, addresses and phone numbers. The
 * deduplication report records the channel of each side of every match so that a reviewer
 * can see at a glance whether a near-match is plausibly one family or two.
 */
public enum Channel {
    /** Submitted by the applicant through the online form. */
    ONLINE,
    /** Submitted on paper and later keyed in from the physical form. */
    PAPER_KEYED
}
