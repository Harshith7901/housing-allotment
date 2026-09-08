package dev.harshith.housing.persistence;

import dev.harshith.housing.core.model.Draw;
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

/**
 * The outcome of one draw for one applicant — stored for <em>every</em> applicant on the
 * roll, not only the winners.
 *
 * <p>Roughly 3,400 rows per draw where 600 would do. That is the deliberate cost of being
 * able to answer the question this system exists for: an applicant asks "why not me?" and
 * gets their ticket, their pool, their rank, the number of seats, and a reason code — in
 * one indexed lookup, months later, without recomputing anything.
 */
@Entity
@Table(name = "draw_selection", indexes = {
        @Index(name = "ix_selection_draw", columnList = "drawId"),
        @Index(name = "ix_selection_application", columnList = "applicationId"),
        @Index(name = "ix_selection_draw_outcome", columnList = "drawId,outcome"),
        @Index(name = "ix_selection_waitlist", columnList = "drawId,poolCode,waitlistPosition")
})
@Getter
@Setter
@NoArgsConstructor
public class DrawSelectionEntity {

    /** {@code <drawId>:<applicationId>} */
    @Id
    @Column(length = 100)
    private String selectionId;

    @Column(nullable = false, length = 64)
    private String drawId;

    @Column(nullable = false, length = 32)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Draw.Outcome outcome;

    @Column(nullable = false, length = 32)
    private String poolCode;

    @Column(nullable = false, length = 64)
    private String ticketHex;

    @Column(nullable = false)
    private int residencyTier;

    @Column(nullable = false)
    private int rankInPool;

    /** Null unless the applicant is on a waitlist. */
    private Integer waitlistPosition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private Draw.ReasonCode reasonCode;

    @Column(nullable = false, length = 2000)
    private String reasonText;

    public static String idFor(String drawId, String applicationId) {
        return drawId + ":" + applicationId;
    }
}
