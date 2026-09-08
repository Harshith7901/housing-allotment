package dev.harshith.housing.persistence;

import dev.harshith.housing.core.dedup.Dedup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One candidate duplicate pair and what was decided about it.
 *
 * <p>The score, the per-field breakdown and the rationale are all stored, not just the
 * verdict. When an applicant says "you struck out my application as a duplicate and it
 * was not", the answer has to be the arithmetic: these were the two forms, this is what
 * agreed and by how much, this is the threshold that was published beforehand, and this
 * is the officer who decided. A boolean {@code is_duplicate} column cannot answer that.
 */
@Entity
@Table(name = "duplicate_link", indexes = {
        @Index(name = "ix_duplicate_left", columnList = "leftApplicationId"),
        @Index(name = "ix_duplicate_right", columnList = "rightApplicationId"),
        @Index(name = "ix_duplicate_review", columnList = "reviewDecision")
})
@Getter
@Setter
@NoArgsConstructor
public class DuplicateLinkEntity {

    /** {@code <leftApplicationId>:<rightApplicationId>}, with the smaller id first. */
    @Id
    @Column(length = 72)
    private String linkId;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Column(nullable = false, length = 32)
    private String leftApplicationId;

    @Column(nullable = false, length = 32)
    private String rightApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Dedup.MatchMethod method;

    @Column(nullable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Dedup.MatchDecision decision;

    /** Per-field breakdown, one {@code field=weight*score} entry per line. */
    @Column(length = 4000)
    private String features;

    @Column(length = 2000)
    private String rationale;

    @Column(length = 500)
    private String sharedBlockingKeys;

    @Column(nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewDecision reviewDecision;

    @Column(length = 128)
    private String reviewedBy;

    private Instant reviewedAt;

    @Column(length = 1000)
    private String reviewNote;

    public static String idFor(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }
}
