package dev.harshith.housing.demo;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.core.sim.SyntheticData;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.EligibilityCheckEntity;
import dev.harshith.housing.persistence.FlatUnitEntity;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.persistence.repo.EligibilityCheckRepository;
import dev.harshith.housing.persistence.repo.FlatUnitRepository;
import dev.harshith.housing.service.AuditService;
import dev.harshith.housing.support.Csv;
import dev.harshith.housing.support.GovernmentIdHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bulk loading of the demo dataset.
 *
 * <p>Two shortcuts are taken here, and both are shortcuts of the <em>fixture</em> rather
 * than of the system, which is why they live in a package that only loads under the
 * {@code demo} flag:
 *
 * <ul>
 *   <li>Applications are inserted through the repository rather than through
 *       {@code IntakeService}, and eligibility decisions likewise. Going through the real
 *       services would mean 8,000 separate transactions and 8,000 audit events at startup.
 *       Each bulk stage instead records a single audit event describing what was loaded, so
 *       the chain still covers it honestly rather than pretending the rows appeared from
 *       nowhere.</li>
 *   <li>Eligibility outcomes come from the generator's own flags instead of documents.</li>
 * </ul>
 *
 * <p>Everything after intake — deduplication, review, freeze, commit, draw, publish,
 * allotment — runs through the real services with the real gates and the real
 * maker–checker rules.
 */
@Service
public class DemoDataService {

    private final ApplicationRepository applications;
    private final EligibilityCheckRepository checks;
    private final FlatUnitRepository units;
    private final GovernmentIdHasher idHasher;
    private final AuditService audit;
    private final Clock clock;

    public DemoDataService(ApplicationRepository applications,
                           EligibilityCheckRepository checks,
                           FlatUnitRepository units,
                           GovernmentIdHasher idHasher,
                           AuditService audit,
                           Clock clock) {
        this.applications = applications;
        this.checks = checks;
        this.units = units;
        this.idHasher = idHasher;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public int loadUnits(Actor actor, String schemeCode, SyntheticData.Dataset data) {
        List<FlatUnitEntity> rows = new ArrayList<>(data.inventory().size());
        data.inventory().forEach(u -> {
            FlatUnitEntity row = new FlatUnitEntity();
            row.setUnitId(u.unitId());
            row.setSchemeCode(schemeCode);
            row.setBlock(u.block());
            row.setUnitType(u.unitType());
            row.setFloorNumber(u.floor());
            row.setWithdrawn(false);
            rows.add(row);
        });
        units.saveAll(rows);
        audit.append(actor, "UNITS_REGISTERED", "SCHEME", schemeCode,
                "count=" + rows.size() + " source=demo-fixture");
        return rows.size();
    }

    @Transactional
    public int loadApplications(Actor actor, String schemeCode, SyntheticData.Dataset data) {
        List<ApplicationEntity> rows = new ArrayList<>(data.applications().size());
        for (SyntheticData.SyntheticApplication app : data.applications()) {
            var identity = app.identity();
            ApplicationEntity row = new ApplicationEntity();
            row.setApplicationId(identity.applicationId());
            row.setSchemeCode(schemeCode);
            row.setChannel(identity.channel());
            row.setSubmittedAt(identity.submittedAt());
            row.setBatchId(identity.channel() == dev.harshith.housing.core.model.Channel.PAPER_KEYED
                    ? "BATCH-DEMO" : null);
            row.setIdempotencyKey(null);
            row.setFullName(identity.fullName());
            row.setRelativeName(identity.relativeName());
            // Note the identifier is hashed on the way in, exactly as production intake
            // does it: the demo exercises the same privacy path, and the matcher therefore
            // has to work against digests rather than numbers.
            row.setGovernmentIdHash(idHasher.hash(identity.governmentId()));
            row.setGovernmentIdLast4(idHasher.last4(identity.governmentId()));
            row.setPhone(identity.phone());
            row.setDateOfBirth(identity.dateOfBirth());
            row.setAddressLine(identity.addressLine());
            row.setWardCode(identity.wardCode());
            row.setResidencyYears(app.residencyYears());
            row.setVerticalCode(app.verticalCode());
            row.setHorizontalCodes(Csv.join(new java.util.TreeSet<>(app.horizontalCodes())));
            row.setUnitTypePreferences(Csv.join(app.unitTypePreferences()));
            row.setStatus(ApplicationStatus.RECEIVED);
            row.setReceivedBy(identity.channel() == dev.harshith.housing.core.model.Channel.PAPER_KEYED
                    ? "clerk.07@scheme" : "self-service");
            row.setCreatedAt(clock.instant());
            row.setUpdatedAt(clock.instant());
            rows.add(row);
        }
        applications.saveAll(rows);
        audit.append(actor, "INTAKE_BULK_LOADED", "SCHEME", schemeCode,
                "applications=" + rows.size()
                        + " households=" + data.intendedHouseholds()
                        + " plantedDuplicates=" + data.intendedDuplicates()
                        + " source=demo-fixture");
        return rows.size();
    }

    /**
     * Records one eligibility decision per application and derives the status, in bulk.
     * Superseded applications are skipped: they carry no ticket, so their eligibility is
     * moot and overwriting the status would lose the reason they hold no ticket.
     */
    @Transactional
    public long verifyAll(Actor actor, String schemeCode, SyntheticData.Dataset data) {
        List<SyntheticData.SyntheticApplication> generated = data.applications();
        List<ApplicationEntity> rows = applications.findBySchemeCodeOrderByApplicationIdAsc(schemeCode);

        java.util.Map<String, SyntheticData.SyntheticApplication> byId = new java.util.HashMap<>();
        generated.forEach(a -> byId.put(a.identity().applicationId(), a));

        List<EligibilityCheckEntity> newChecks = new ArrayList<>();
        long eligible = 0;
        long ineligible = 0;

        for (ApplicationEntity row : rows) {
            if (row.getStatus() == ApplicationStatus.SUPERSEDED) {
                continue;
            }
            SyntheticData.SyntheticApplication source = byId.get(row.getApplicationId());
            boolean passed = source == null || source.eligible();
            String reason = passed ? null : source.ineligibilityReason();

            EligibilityCheckEntity check = new EligibilityCheckEntity();
            check.setCheckId("ELG-" + UUID.randomUUID());
            check.setApplicationId(row.getApplicationId());
            check.setCheckCode(passed ? "SCHEME_CONDITIONS" : reasonCode(reason));
            check.setPassed(passed);
            check.setReason(passed ? "All published scheme conditions satisfied" : reason);
            check.setEvidenceRef("DEMO-REGISTER/" + row.getApplicationId());
            check.setDecidedBy(actor.id());
            check.setDecidedAt(clock.instant());
            newChecks.add(check);

            row.setStatus(passed ? ApplicationStatus.ELIGIBLE : ApplicationStatus.INELIGIBLE);
            row.setStatusReason(passed
                    ? "All recorded eligibility checks passed"
                    : check.getCheckCode() + ": " + reason);
            row.setUpdatedAt(clock.instant());
            if (passed) {
                eligible++;
            } else {
                ineligible++;
            }
        }

        checks.saveAll(newChecks);
        applications.saveAll(rows);
        audit.append(actor, "VERIFICATION_BULK_RECORDED", "SCHEME", schemeCode,
                "checksRecorded=" + newChecks.size()
                        + " eligible=" + eligible
                        + " ineligible=" + ineligible
                        + " source=demo-fixture");
        return eligible;
    }

    private String reasonCode(String reason) {
        if (reason == null) {
            return "SCHEME_CONDITIONS";
        }
        int colon = reason.indexOf(':');
        return colon > 0 ? reason.substring(0, colon) : "SCHEME_CONDITIONS";
    }
}
