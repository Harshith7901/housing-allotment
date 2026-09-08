package dev.harshith.housing.core.units;

import java.util.List;

/** Value objects for the physical inventory and its assignment. */
public final class Units {

    private Units() {
    }

    /**
     * One flat.
     *
     * @param unitType the applicant-visible category (e.g. {@code ONE_BHK}); preferences
     *                 are expressed over these values
     */
    public record UnitInventoryItem(String unitId, String block, String unitType, int floor) {
        public UnitInventoryItem {
            if (unitId == null || unitId.isBlank()) {
                throw new IllegalArgumentException("unitId is required");
            }
            if (unitType == null || unitType.isBlank()) {
                throw new IllegalArgumentException("unitType is required for unit " + unitId);
            }
        }
    }

    /**
     * @param preferenceRankHonoured 1 when the applicant received their first choice, 2
     *                               their second, and 0 when no stated preference could be
     *                               met and a unit was assigned by the fallback rule
     */
    public record UnitAllotment(
            String applicationId,
            String unitId,
            String unitType,
            String block,
            int pickOrder,
            int preferenceRankHonoured,
            String basis
    ) {
    }

    public record AllotmentPlan(
            String drawId,
            List<UnitAllotment> allotments,
            List<String> applicantsWithoutUnit,
            List<String> unassignedUnitIds,
            List<String> workings,
            String allotmentHash
    ) {
        public AllotmentPlan {
            allotments = List.copyOf(allotments == null ? List.of() : allotments);
            applicantsWithoutUnit = List.copyOf(applicantsWithoutUnit == null ? List.of() : applicantsWithoutUnit);
            unassignedUnitIds = List.copyOf(unassignedUnitIds == null ? List.of() : unassignedUnitIds);
            workings = List.copyOf(workings == null ? List.of() : workings);
        }
    }
}
