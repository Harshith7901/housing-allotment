package dev.harshith.housing.persistence;

import dev.harshith.housing.core.model.Channel;
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
import java.time.LocalDate;

/**
 * One application form.
 *
 * <p>Two things about the identity fields are worth stating, because they are choices
 * rather than defaults.
 *
 * <p><b>The government identifier is stored only as a keyed hash plus its last four
 * digits.</b> Duplicate detection needs to know whether two forms carry the <em>same</em>
 * identifier; it never needs to know what the identifier is. Storing the hash gives exact
 * matching, and the last four digits let a clerk confirm a number against a physical
 * document without the database holding a usable copy of it. It is the single highest-value
 * privacy decision available here: a leak of this table does not leak identity numbers.
 *
 * <p><b>The application id is immutable and issued at intake.</b> The lottery ticket is
 * derived from it, so a reissued or recycled id would change somebody's ticket. Nothing in
 * the system updates this column.
 */
@Entity
@Table(name = "application", indexes = {
        @Index(name = "ix_application_scheme_status", columnList = "schemeCode,status"),
        @Index(name = "ix_application_cluster", columnList = "clusterId"),
        @Index(name = "ix_application_gid", columnList = "governmentIdHash"),
        @Index(name = "ix_application_phone", columnList = "phone")
})
@Getter
@Setter
@NoArgsConstructor
public class ApplicationEntity {

    @Id
    @Column(length = 32)
    private String applicationId;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(nullable = false)
    private Instant submittedAt;

    /** The physical batch of paper forms this was keyed from; null for online. */
    @Column(length = 64)
    private String batchId;

    /**
     * Caller-supplied key that makes intake idempotent. A retried submission with the same
     * key returns the original application instead of creating a second one — which
     * removes one whole source of the duplicates this scheme has to deal with: the
     * applicant who pressed submit twice because the page hung.
     */
    @Column(length = 128, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, length = 200)
    private String fullName;

    @Column(length = 200)
    private String relativeName;

    /**
     * Never serialised out of the application. The digest is an internal matching key; an
     * API that returns it lets a caller confirm a guessed identifier by comparing digests,
     * which is most of the way to defeating the point of hashing it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 64)
    private String governmentIdHash;

    @Column(length = 8)
    private String governmentIdLast4;

    @Column(length = 24)
    private String phone;

    private LocalDate dateOfBirth;

    @Column(length = 500)
    private String addressLine;

    @Column(length = 32)
    private String wardCode;

    @Column(nullable = false)
    private int residencyYears;

    @Column(nullable = false, length = 32)
    private String verticalCode;

    /** Comma-separated horizontal quota codes, e.g. {@code WOMEN,PWD}. */
    @Column(length = 500)
    private String horizontalCodes;

    /** Comma-separated ordered unit type preferences, e.g. {@code TWO_BHK,ONE_BHK}. */
    @Column(length = 500)
    private String unitTypePreferences;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;

    @Column(length = 64)
    private String clusterId;

    /** Set when this form was confirmed to be a re-submission of another one. */
    @Column(length = 32)
    private String supersededByApplicationId;

    @Column(length = 500)
    private String statusReason;

    /**
     * Who keyed this form in. Held so that maker–checker can be enforced: the clerk who
     * entered an application may not be the officer who verifies it.
     */
    @Column(length = 128)
    private String receivedBy;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private long version;
}
