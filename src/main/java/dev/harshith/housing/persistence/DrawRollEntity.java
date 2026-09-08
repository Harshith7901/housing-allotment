package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A frozen roll: immutable once written. */
@Entity
@Table(name = "draw_roll")
@Getter
@Setter
@NoArgsConstructor
public class DrawRollEntity {

    @Id
    @Column(length = 64)
    private String rollId;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Column(nullable = false, length = 64)
    private String ruleSetVersion;

    @Column(nullable = false, length = 64)
    private String ruleSetHash;

    @Column(nullable = false)
    private Instant frozenAt;

    @Column(nullable = false, length = 128)
    private String frozenBy;

    @Column(nullable = false, length = 64)
    private String rollHash;

    @Column(nullable = false)
    private int entryCount;

    /** How many units the inventory held when this roll was frozen. */
    @Column(nullable = false)
    private int inventoryCount;

    /** Applications excluded at freeze time, with counts by reason, for the record. */
    @Column(length = 4000)
    private String exclusionSummary;

    /** Set when a later roll supersedes this one, so the sequence is auditable. */
    @Column(length = 64)
    private String supersededByRollId;
}
