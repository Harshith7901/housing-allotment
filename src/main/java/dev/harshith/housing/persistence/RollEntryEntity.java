package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of a frozen roll: the attributes an application was drawn under.
 *
 * <p>This duplicates columns that also exist on {@link ApplicationEntity}, and that is the
 * point. The application row is a living record that can be corrected before the freeze
 * and whose ward or category may legitimately be amended for a later draw. The roll entry
 * is the snapshot the draw actually used, and it is part of the hashed roll. Reading the
 * category from the application row at draw time would mean a later correction silently
 * rewrites history.
 *
 * <p>Composite keys are avoided throughout in favour of a single derived string id. It
 * keeps the JPA mapping trivial and the ids readable in logs, at the cost of a slightly
 * larger index.
 */
@Entity
@Table(name = "roll_entry", indexes = {
        @Index(name = "ix_roll_entry_roll", columnList = "rollId"),
        @Index(name = "ix_roll_entry_application", columnList = "applicationId")
})
@Getter
@Setter
@NoArgsConstructor
public class RollEntryEntity {

    /** {@code <rollId>:<applicationId>} */
    @Id
    @Column(length = 100)
    private String entryId;

    @Column(nullable = false, length = 64)
    private String rollId;

    @Column(nullable = false, length = 32)
    private String applicationId;

    @Column(nullable = false, length = 64)
    private String clusterId;

    @Column(nullable = false, length = 32)
    private String verticalCode;

    @Column(length = 500)
    private String horizontalCodes;

    @Column(nullable = false)
    private int residencyYears;

    @Column(length = 500)
    private String unitTypePreferences;

    public static String idFor(String rollId, String applicationId) {
        return rollId + ":" + applicationId;
    }
}
