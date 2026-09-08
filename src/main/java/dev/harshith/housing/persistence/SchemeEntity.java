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

/** One housing scheme, and the single authoritative record of which phase it is in. */
@Entity
@Table(name = "scheme")
@Getter
@Setter
@NoArgsConstructor
public class SchemeEntity {

    @Id
    @Column(length = 32)
    private String schemeCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SchemePhase phase;

    @Column(length = 64)
    private String activeRuleSetVersion;

    @Column(length = 64)
    private String activeRollId;

    private Instant createdAt;

    private Instant phaseChangedAt;

    @Column(length = 128)
    private String phaseChangedBy;

    /**
     * Optimistic locking. Phase transitions are the one place where two concurrent
     * requests could each read {@code VERIFICATION} and both freeze a roll; the version
     * column turns that into a failed update rather than two rolls.
     */
    @Version
    private long version;

    public SchemeEntity(String schemeCode, String name, SchemePhase phase, Instant createdAt) {
        this.schemeCode = schemeCode;
        this.name = name;
        this.phase = phase;
        this.createdAt = createdAt;
        this.phaseChangedAt = createdAt;
    }
}
