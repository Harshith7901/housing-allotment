package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One flat in the inventory. */
@Entity
@Table(name = "flat_unit", indexes = {
        @Index(name = "ix_unit_scheme_type", columnList = "schemeCode,unitType")
})
@Getter
@Setter
@NoArgsConstructor
public class FlatUnitEntity {

    @Id
    @Column(length = 32)
    private String unitId;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Column(length = 32)
    private String block;

    @Column(nullable = false, length = 32)
    private String unitType;

    /** Named {@code floor_number}: FLOOR is a SQL function name in most engines. */
    @Column(name = "floor_number")
    private int floorNumber;

    /**
     * True when the unit is withdrawn from this scheme — construction defect, litigation,
     * reserved for a statutory purpose. Withdrawn units are excluded from the inventory
     * count that the apportionment is computed against, which is why the count is read
     * from here rather than being typed into the rule set by hand.
     */
    @Column(nullable = false)
    private boolean withdrawn;

    @Column(length = 500)
    private String withdrawnReason;
}
