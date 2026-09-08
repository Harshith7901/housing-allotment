package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One eligibility condition, checked against one application, by one named officer.
 *
 * <p>Modelled as several rows rather than one {@code eligible} flag because rejection is
 * the decision most likely to be challenged and "not eligible" is not an answer. The
 * applicant is entitled to know which published condition they failed, what document was
 * looked at, who looked at it and when. Each row carries a reference to the evidence
 * rather than the document itself — file storage is out of scope here — so the reference
 * is what an appeal is heard on.
 */
@Entity
@Table(name = "eligibility_check", indexes = {
        @Index(name = "ix_eligibility_application", columnList = "applicationId")
})
@Getter
@Setter
@NoArgsConstructor
public class EligibilityCheckEntity {

    @Id
    @Column(length = 72)
    private String checkId;

    @Column(nullable = false, length = 32)
    private String applicationId;

    /** e.g. {@code INCOME_CEILING}, {@code NO_EXISTING_PROPERTY}, {@code RESIDENCY_PROOF}. */
    @Column(nullable = false, length = 64)
    private String checkCode;

    @Column(nullable = false)
    private boolean passed;

    /** Reason code plus explanation, shown verbatim to the applicant. */
    @Column(length = 1000)
    private String reason;

    /** Pointer to the document relied on — a file store key or a physical register entry. */
    @Column(length = 300)
    private String evidenceRef;

    @Column(nullable = false, length = 128)
    private String decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;
}
