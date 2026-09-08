package dev.harshith.housing.persistence;

/** Where a single draw has got to. */
public enum DrawStatus {
    /** A roll is frozen and a seed commitment has been published. Not yet drawn. */
    COMMITTED,
    /** Executed. The result exists and is hashed, but is not yet public. */
    EXECUTED,
    /** Announced. The result is immutable and the verification bundle is downloadable. */
    PUBLISHED,
    /**
     * Set aside — by the authority on discovering a defect, or on appeal. The record and
     * its result stay in the database forever; a superseding draw refers back to it.
     */
    ANNULLED
}
