package dev.harshith.housing.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The frozen electoral roll of the draw.
 *
 * <p>The roll is the hinge of the whole system. Before it is frozen, applications can be
 * corrected, merged and rejected freely. After it is frozen, the set of people in the
 * draw and the attributes they are drawn under are fixed and hashed, and any further
 * change requires a new roll with a new hash and a new, separately authorised draw.
 *
 * <p>Note what a roll entry deliberately does <em>not</em> contain: no name, no phone
 * number, no government ID, no address. The roll is designed to be published in full so
 * that anyone can recompute the result, and that is only safe if the roll carries the
 * decision-relevant attributes and nothing else. The link from an application id back to
 * a human being stays in the application table, behind authorisation.
 */
public final class Roll {

    private Roll() {
    }

    public record RollEntry(
            String applicationId,
            String clusterId,
            String verticalCode,
            Set<String> horizontalCodes,
            int residencyYears,
            List<String> unitTypePreferences
    ) {
        public RollEntry {
            if (applicationId == null || applicationId.isBlank()) {
                throw new IllegalArgumentException("applicationId is required on a roll entry");
            }
            if (verticalCode == null || verticalCode.isBlank()) {
                throw new IllegalArgumentException("verticalCode is required on roll entry " + applicationId);
            }
            if (residencyYears < 0) {
                throw new IllegalArgumentException("residencyYears must be >= 0 on roll entry " + applicationId);
            }
            // A TreeSet keeps the horizontal codes in a stable order, which is what makes
            // the canonical encoding of this entry reproducible.
            horizontalCodes = java.util.Collections.unmodifiableSortedSet(
                    new TreeSet<>(horizontalCodes == null ? Set.<String>of() : horizontalCodes));
            unitTypePreferences = List.copyOf(unitTypePreferences == null ? List.of() : unitTypePreferences);
            if (clusterId == null || clusterId.isBlank()) {
                clusterId = applicationId;
            }
        }

        public boolean satisfies(String horizontalCode) {
            return horizontalCodes.contains(horizontalCode);
        }
    }

    /**
     * @param rollId         internal identifier
     * @param frozenAt       truncated to whole seconds, because it is hashed
     * @param entries        sorted by application id by {@code RollBuilder}; the sort is
     *                       part of the canonical form, never an incidental DB ordering
     * @param rollHash       SHA-256 of the canonical encoding of everything above
     */
    public record DrawRoll(
            String rollId,
            String schemeCode,
            String ruleSetVersion,
            String ruleSetHash,
            Instant frozenAt,
            List<RollEntry> entries,
            String rollHash
    ) {
        public DrawRoll {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }

        public int entryCount() {
            return entries.size();
        }
    }
}
