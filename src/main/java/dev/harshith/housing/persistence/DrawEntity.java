package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One draw, from commitment to publication.
 *
 * <p>The nonce column is the sensitive one: while the draw is in
 * {@link DrawStatus#COMMITTED} it must not be readable by anyone who could use advance
 * knowledge of it, and after execution it must be readable by everyone so the commitment
 * can be checked. In this implementation the API simply refuses to return it before
 * execution. In a real deployment it belongs in a key vault or in a sealed envelope with a
 * separate custodian, and the README lists that as a deliberate omission rather than
 * pretending a database column is a secret.
 *
 * <p>Note what is <em>not</em> stored: the per-pool rankings, all 3,400 ticket orderings.
 * They are a pure function of the roll and the seed, both of which are stored and hashed,
 * so persisting them would add a large table whose only possible role is to disagree with
 * the recomputation. They are recomputed on demand instead.
 */
@Entity
@Table(name = "draw")
@Getter
@Setter
@NoArgsConstructor
public class DrawEntity {

    @Id
    @Column(length = 64)
    private String drawId;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Column(nullable = false, length = 64)
    private String rollId;

    @Column(nullable = false, length = 64)
    private String rollHash;

    @Column(nullable = false, length = 64)
    private String ruleSetVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DrawStatus status;

    // ---- commitment, published before the entropy value is known ----------------

    @Column(nullable = false, length = 64)
    private String commitmentHex;

    @Column(nullable = false)
    private Instant committedAt;

    @Column(nullable = false, length = 128)
    private String committedBy;

    /** The public source named in advance, e.g. "State lottery draw of 25 May 2026". */
    @Column(nullable = false, length = 300)
    private String entropySourceDescription;

    // ---- reveal and execution ---------------------------------------------------

    @Column(length = 300)
    private String publicEntropy;

    @Column(length = 128)
    private String nonce;

    @Column(length = 64)
    private String seedHex;

    private Instant executedAt;

    @Column(length = 128)
    private String executedBy;

    @Column(length = 64)
    private String resultHash;

    /** Canonical seat plan text, including the apportionment arithmetic. */
    @Column(length = 200_000)
    private String seatPlan;

    /** Per-pool summary and the horizontal top-up notes. */
    @Column(length = 200_000)
    private String poolSummary;

    private Instant publishedAt;

    @Column(length = 128)
    private String publishedBy;

    @Column(length = 1000)
    private String annulmentReason;

    @Version
    private long version;
}
