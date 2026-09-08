package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One flat offered to one applicant.
 *
 * <p>Forfeiture does not delete the row. The vacated offer stays, marked
 * {@link AllotmentStatus#FORFEITED}, and the promotion that fills it is a new row pointing
 * back at it, so the chain of custody of every individual flat is readable end to end.
 *
 * <p>That history is exactly why the "one live offer per flat" rule cannot be a unique
 * index on {@code (draw_id, unit_id)}: a forfeited row and its replacement legitimately
 * share both. It is enforced instead by {@link #liveUnitKey} — see that field. The
 * guarantee belongs in the database rather than in application code because it has to hold
 * across two concurrent requests and a mid-transaction restart, and because two families
 * holding papers for the same flat is the one failure here that cannot be walked back.
 */
@Entity
@Table(name = "unit_allotment", indexes = {
        @Index(name = "ix_allotment_draw", columnList = "drawId"),
        @Index(name = "ix_allotment_application", columnList = "applicationId"),
        @Index(name = "ix_allotment_unit", columnList = "drawId,unitId,status")
})
@Getter
@Setter
@NoArgsConstructor
public class UnitAllotmentEntity {

    @Id
    @Column(length = 140)
    private String allotmentId;

    @Column(nullable = false, length = 64)
    private String drawId;

    @Column(nullable = false, length = 32)
    private String applicationId;

    @Column(nullable = false, length = 32)
    private String unitId;

    @Column(nullable = false, length = 32)
    private String unitType;

    @Column(length = 32)
    private String block;

    /** Position in the ticket-ordered pick sequence, so the assignment is replayable. */
    @Column(nullable = false)
    private int pickOrder;

    @Column(nullable = false)
    private int preferenceRankHonoured;

    @Column(nullable = false, length = 500)
    private String basis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AllotmentStatus status;

    /**
     * {@code <drawId>:<unitId>} while this offer is live, and NULL once it is forfeited.
     *
     * <p>A unique index on a nullable column permits any number of NULLs in both MySQL and
     * H2, so this expresses "at most one live offer per flat per draw" as a database
     * constraint while leaving the full forfeiture history in place. Maintained by
     * {@code AllotmentService}; never set by hand.
     */
    @Column(name = "live_unit_key", length = 100, unique = true)
    private String liveUnitKey;

    @Column(nullable = false)
    private Instant offeredAt;

    private Instant decidedAt;

    @Column(length = 128)
    private String decidedBy;

    @Column(length = 1000)
    private String decisionReason;

    /** For a promotion, the forfeited allotment whose seat this fills. */
    @Column(length = 140)
    private String fillsVacancyOf;

    @Version
    private long version;

    public static String liveKeyFor(String drawId, String unitId) {
        return drawId + ":" + unitId;
    }
}
