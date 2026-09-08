package dev.harshith.housing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.harshith.housing.core.model.Channel;
import dev.harshith.housing.persistence.ReviewDecision;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.service.AuditService;
import dev.harshith.housing.web.dto.IntakeRequest;
import dev.harshith.housing.web.dto.Requests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole scheme, driven through HTTP exactly as an operator would drive it.
 *
 * <p>Deliberately one long test rather than several short ones. The lifecycle is a state
 * machine whose interesting properties are all about <em>order</em> — that a correction is
 * refused after the freeze, that a draw cannot be executed twice, that the officer who
 * committed the seed cannot execute it — and those cannot be asserted from a fixture that
 * starts in the middle. Getting to phase {@code ALLOTMENT} legitimately is itself the
 * assertion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchemeLifecycleIT {

    private static final String SCHEME = "SCH-IT-01";
    private static final String RULE_SET = "IT-PHASE-1";
    private static final String ROLL = "ROLL-IT-1";
    private static final String DRAW = "DRAW-IT-1";

    private static final String REGISTRAR = "registrar@scheme";
    private static final String RETURNING_OFFICER = "returning.officer@scheme";
    private static final String REVIEWER = "reviewer.02@scheme";
    private static final String CLERK = "clerk.07@scheme";

    private static final int UNITS = 40;
    private static final int HOUSEHOLDS = 120;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AuditService audit;

    @Test
    @DisplayName("a scheme runs end to end, and every gate along the way holds")
    void fullLifecycle() throws Exception {
        // ---- 1. the scheme and its published rules --------------------------------
        mvc.perform(admin(post("/api/admin/schemes"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.CreateScheme(SCHEME, "Integration scheme"))))
                .andExpect(status().isCreated());

        MvcResult published = mvc.perform(admin(post("/api/admin/rule-sets"), REGISTRAR)
                        .content(json.writeValueAsString(ruleSet())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode ruleSetBody = json.readTree(published.getResponse().getContentAsString());
        assertEquals(64, ruleSetBody.get("ruleSetHash").asText().length());
        assertTrue(ruleSetBody.get("canonicalText").asText().contains("totalUnits=" + UNITS),
                "the hashed text must be returned so it can be published verbatim");

        int apportioned = 0;
        for (JsonNode category : ruleSetBody.get("seatPlan")) {
            apportioned += category.get("seats").asInt();
        }
        assertEquals(UNITS, apportioned, "the apportionment must account for every flat");

        // A rule set is immutable: republishing the same version is a conflict, not an update.
        mvc.perform(admin(post("/api/admin/rule-sets"), REGISTRAR)
                        .content(json.writeValueAsString(ruleSet())))
                .andExpect(status().isConflict());

        // ---- 2. inventory ---------------------------------------------------------
        List<Requests.Unit> units = new ArrayList<>();
        for (int i = 1; i <= UNITS; i++) {
            units.add(new Requests.Unit(String.format("U-%03d", i), "B" + (char) ('A' + i % 3),
                    i % 2 == 0 ? "TWO_BHK" : "ONE_BHK", i % 5));
        }
        mvc.perform(admin(post("/api/admin/schemes/" + SCHEME + "/units"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.AddUnits(units))))
                .andExpect(status().isCreated());

        // ---- 3. intake ------------------------------------------------------------
        List<String> applicationIds = new ArrayList<>();
        for (int i = 1; i <= HOUSEHOLDS; i++) {
            applicationIds.add(submit(intake(i)));
        }

        // The same household submits twice through the online form, with a typo on the
        // second attempt. This is the case the brief calls out.
        String firstForm = applicationIds.get(0);
        IntakeRequest original = intake(1);
        String duplicate = submit(new IntakeRequest(SCHEME, Channel.PAPER_KEYED,
                Instant.parse("2026-04-11T06:00:00Z"), "BATCH-1", null,
                "LAKHSMI RAO",                                  // two letters transposed
                original.relativeName(),
                "5000 0000 0001",                               // same identifier, spaced
                "9800000091",                                   // digits transposed
                original.dateOfBirth(),
                original.addressLine().replace("ROAD", "RD"),
                original.wardCode(), original.residencyYears(),
                original.verticalCode(), original.horizontalCodes(), List.of("TWO_BHK")));

        // Idempotency: an applicant presses submit twice with the same key. A distinct
        // identity, so that this exercises the idempotency key rather than the matcher.
        IntakeRequest retried = withKey(intake(500), "retry-key-1");
        String once = submit(retried);
        MvcResult twice = mvc.perform(actor(post("/api/applications"), CLERK, "DATA_ENTRY")
                        .content(json.writeValueAsString(retried)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode retryBody = json.readTree(twice.getResponse().getContentAsString());
        assertEquals(once, retryBody.get("applicationId").asText(),
                "a retried submission must return the original application, not create a second");
        assertTrue(retryBody.get("deduplicated").asBoolean());

        // ---- 4. corrections are allowed now, and only now -------------------------
        mvc.perform(actor(put("/api/applications/" + firstForm), REVIEWER, "VERIFIER")
                        .content(json.writeValueAsString(new Requests.Correction(
                                withWard(intake(1), "W-12"), "ward misread from the paper form"))))
                .andExpect(status().isOk());

        // A correction without a reason is refused.
        mvc.perform(actor(put("/api/applications/" + firstForm), REVIEWER, "VERIFIER")
                        .content(json.writeValueAsString(new Requests.Correction(intake(1), " "))))
                .andExpect(status().isBadRequest());

        // ---- 5. deduplication -----------------------------------------------------
        // It cannot run during intake.
        mvc.perform(admin(post("/api/admin/schemes/" + SCHEME + "/deduplication"), REVIEWER))
                .andExpect(status().isConflict());

        advance(SchemePhase.INTAKE_CLOSED);
        advance(SchemePhase.DEDUPLICATION);

        MvcResult dedupResult = mvc.perform(actor(
                        post("/api/admin/schemes/" + SCHEME + "/deduplication"), REVIEWER, "VERIFIER"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode dedup = json.readTree(dedupResult.getResponse().getContentAsString());
        assertTrue(dedup.get("autoLinked").asInt() >= 1,
                "the planted re-submission shares a government identifier and must be linked");
        assertTrue(dedup.get("comparisons").asInt() < HOUSEHOLDS * (HOUSEHOLDS - 1) / 2,
                "blocking must reduce the comparison count");

        // The duplicate keeps its row and points at the surviving application.
        JsonNode supersededView = getJson("/api/applications/" + duplicate);
        assertEquals("SUPERSEDED", supersededView.get("status").asText());
        assertEquals(firstForm, supersededView.get("supersededByApplicationId").asText());

        // Work whatever landed in the review queue, so the freeze gate can open.
        for (JsonNode link : getJson("/api/admin/schemes/" + SCHEME + "/duplicate-review-queue")) {
            mvc.perform(actor(post("/api/admin/duplicate-links/"
                                    + link.get("linkId").asText() + "/review"), REVIEWER, "VERIFIER")
                            .content(json.writeValueAsString(new Requests.ReviewDuplicate(
                                    ReviewDecision.DIFFERENT_HOUSEHOLDS, "distinct on inspection"))))
                    .andExpect(status().isOk());
        }

        // ---- 6. verification ------------------------------------------------------
        advance(SchemePhase.VERIFICATION);

        // Freezing is refused while applications have no recorded decision.
        mvc.perform(admin(post("/api/admin/schemes/" + SCHEME + "/rolls"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.FreezeRoll(ROLL))))
                .andExpect(status().isConflict());

        // Everybody on record gets an evidenced decision. One is rejected, with a reason.
        List<String> everyone = new ArrayList<>(applicationIds);
        everyone.add(duplicate);
        everyone.add(once);
        String rejected = applicationIds.get(5);
        for (String applicationId : everyone) {
            boolean pass = !applicationId.equals(rejected);
            mvc.perform(actor(post("/api/applications/" + applicationId + "/eligibility-checks"),
                            REVIEWER, "VERIFIER")
                            .content(json.writeValueAsString(new Requests.RecordEligibility(
                                    pass ? "SCHEME_CONDITIONS" : "INCOME_ABOVE_CEILING",
                                    pass,
                                    pass ? null : "Declared income exceeds the published ceiling",
                                    "REGISTER/" + applicationId))))
                    .andExpect(status().isCreated());
        }

        JsonNode rejectedView = getJson("/api/applications/" + rejected);
        assertEquals("INELIGIBLE", rejectedView.get("status").asText());
        assertTrue(rejectedView.get("statusReason").asText().contains("INCOME_ABOVE_CEILING"),
                "a rejection must carry the published condition it failed");

        // A clerk may not verify: the maker cannot be the checker.
        mvc.perform(actor(post("/api/applications/" + applicationIds.get(2) + "/eligibility-checks"),
                        CLERK, "DATA_ENTRY")
                        .content(json.writeValueAsString(new Requests.RecordEligibility(
                                "SCHEME_CONDITIONS", true, null, "REGISTER/x"))))
                .andExpect(status().isForbidden());

        // ---- 7. the freeze --------------------------------------------------------
        MvcResult frozen = mvc.perform(admin(post("/api/admin/schemes/" + SCHEME + "/rolls"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.FreezeRoll(ROLL))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode roll = json.readTree(frozen.getResponse().getContentAsString());
        int entryCount = roll.get("entryCount").asInt();
        assertEquals(64, roll.get("rollHash").asText().length());
        assertTrue(entryCount > 0 && entryCount < everyone.size(),
                "the roll must exclude the superseded and the ineligible: " + entryCount);

        // The canonical roll text must hash to the published roll hash, using nothing but
        // sha256sum. This is the property the whole verification story rests on.
        String rollText = mvc.perform(get("/api/admin/rolls/" + ROLL + "/canonical"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(roll.get("rollHash").asText(),
                dev.harshith.housing.core.util.Hashing.sha256Hex(rollText));
        assertTrue(rollText.contains("entryCount=" + entryCount));
        assertTrue(!rollText.contains("KUMAR"), "the roll must carry no names");

        // Corrections are now refused. This is the point of the whole phase machine.
        mvc.perform(actor(put("/api/applications/" + applicationIds.get(1)), REVIEWER, "VERIFIER")
                        .content(json.writeValueAsString(new Requests.Correction(
                                withWard(intake(2), "W-99"), "late change of address"))))
                .andExpect(status().isConflict());

        // ---- 8. commit and reveal -------------------------------------------------
        MvcResult committedResult = mvc.perform(admin(
                        post("/api/admin/schemes/" + SCHEME + "/draws"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.CommitSeed(DRAW,
                                "Winning number of the State lottery draw of 25 May 2026"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode committed = json.readTree(committedResult.getResponse().getContentAsString());
        String commitment = committed.get("commitmentHex").asText();
        assertEquals(64, commitment.length());
        assertTrue(committed.get("nonce") == null && committed.get("revealedNonce") == null,
                "the nonce must not be disclosed before the draw, or the commitment is worthless");

        // The officer who committed the seed may not also execute the draw.
        mvc.perform(admin(post("/api/admin/draws/" + DRAW + "/execute"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.ExecuteDraw("LOTTERY:481902"))))
                .andExpect(status().isForbidden());

        MvcResult executedResult = mvc.perform(admin(
                        post("/api/admin/draws/" + DRAW + "/execute"), RETURNING_OFFICER)
                        .content(json.writeValueAsString(new Requests.ExecuteDraw("LOTTERY:481902"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode executed = json.readTree(executedResult.getResponse().getContentAsString());
        assertEquals(UNITS, executed.get("selected").asInt());
        assertEquals(entryCount, executed.get("candidates").asInt(),
                "every applicant on the roll must have an outcome, not only the winners");

        String nonce = executed.get("revealedNonce").asText();
        assertEquals(commitment,
                dev.harshith.housing.core.util.Hashing.sha256Hex("commit/1|" + nonce),
                "the revealed nonce must match the commitment published beforehand");
        assertEquals(executed.get("seedHex").asText(),
                dev.harshith.housing.core.util.Hashing.sha256Hex(
                        "seed/1|" + roll.get("rollHash").asText() + "|LOTTERY:481902|" + nonce),
                "a member of the public must be able to recompute the seed");

        // A draw runs once.
        mvc.perform(admin(post("/api/admin/draws/" + DRAW + "/execute"), RETURNING_OFFICER)
                        .content(json.writeValueAsString(new Requests.ExecuteDraw("LOTTERY:999999"))))
                .andExpect(status().isConflict());

        // Nothing is public until it is announced.
        mvc.perform(get("/api/public/draws/" + DRAW)).andExpect(status().isConflict());

        // ---- 9. publication -------------------------------------------------------
        mvc.perform(admin(post("/api/admin/draws/" + DRAW + "/publish"), REGISTRAR))
                .andExpect(status().isOk());

        JsonNode publicView = getJson("/api/public/draws/" + DRAW);
        assertEquals(executed.get("resultHash").asText(), publicView.get("resultHash").asText());
        assertTrue(publicView.get("resultVerifiedByRecomputation").asBoolean(),
                "the published result is recomputed from the roll and the seed on every read");

        String resultText = mvc.perform(get("/api/public/draws/" + DRAW + "/result"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(publicView.get("resultHash").asText(),
                dev.harshith.housing.core.util.Hashing.sha256Hex(resultText));
        assertEquals(entryCount, resultText.lines().filter(l -> l.startsWith("sel=")).count());

        // ---- 10. what an applicant is told ---------------------------------------
        JsonNode explanation = getJson("/api/public/applications/" + applicationIds.get(1) + "/explanation");
        JsonNode drawLine = explanation.get("draws").get(0);
        assertNotNull(drawLine.get("reasonCode").asText());
        assertTrue(drawLine.get("candidatesInPool").asInt() > 0);
        assertTrue(explanation.get("howToVerify").get("ticketFormula").asText().contains("ticket/1"),
                "the applicant is handed the arithmetic, not merely the verdict");

        JsonNode rejectionExplanation = getJson("/api/public/applications/" + rejected + "/explanation");
        assertEquals("INELIGIBLE", rejectionExplanation.get("status").asText());
        assertEquals(1, rejectionExplanation.get("eligibilityChecks").size());
        assertEquals("INCOME_ABOVE_CEILING",
                rejectionExplanation.get("eligibilityChecks").get(0).get("checkCode").asText());
        assertTrue(rejectionExplanation.get("draws").isEmpty(),
                "somebody excluded before the draw has no draw line, and is told why instead");

        // ---- 11. the verification bundle -----------------------------------------
        byte[] zip = mvc.perform(get("/api/public/draws/" + DRAW + "/verification-bundle"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(zip.length > 0);

        String manifest = mvc.perform(get("/api/public/draws/" + DRAW
                        + "/verification-bundle/manifest"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(manifest.contains("rollHash=" + roll.get("rollHash").asText()));
        assertTrue(manifest.contains("resultHash=" + publicView.get("resultHash").asText()));
        assertTrue(manifest.contains("auditHeadHash="));

        // ---- 12. allotment, forfeiture, promotion --------------------------------
        MvcResult assigned = mvc.perform(admin(
                        post("/api/admin/draws/" + DRAW + "/allotments"), REGISTRAR))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(UNITS, json.readTree(assigned.getResponse().getContentAsString())
                .get("assigned").asInt());

        JsonNode allotments = getJson("/api/admin/draws/" + DRAW + "/allotments");
        String toForfeit = allotments.get(0).get("allotmentId").asText();
        String forfeitedUnit = allotments.get(0).get("unitId").asText();

        mvc.perform(admin(post("/api/admin/allotments/" + toForfeit + "/forfeit"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.Reason("payment deadline missed"))))
                .andExpect(status().isOk());

        // Forfeiting requires a reason.
        mvc.perform(admin(post("/api/admin/allotments/"
                        + allotments.get(1).get("allotmentId").asText() + "/forfeit"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.Reason(" "))))
                .andExpect(status().isBadRequest());

        MvcResult promotedResult = mvc.perform(admin(
                        post("/api/admin/draws/" + DRAW + "/waitlist-promotions"), REGISTRAR))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode promotions = json.readTree(promotedResult.getResponse().getContentAsString());
        assertEquals(1, promotions.size());
        assertNotNull(promotions.get(0).get("promotedApplicationId").asText());

        JsonNode afterPromotion = getJson("/api/admin/draws/" + DRAW + "/allotments");
        long liveOffersForThatUnit = 0;
        for (JsonNode row : afterPromotion) {
            if (row.get("unitId").asText().equals(forfeitedUnit)
                    && !row.get("status").asText().equals("FORFEITED")) {
                liveOffersForThatUnit++;
            }
        }
        assertEquals(1, liveOffersForThatUnit,
                "the vacated flat must be offered to exactly one person");

        // Promotion is idempotent: calling it again fills nothing new.
        MvcResult again = mvc.perform(admin(
                        post("/api/admin/draws/" + DRAW + "/waitlist-promotions"), REGISTRAR))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(0, json.readTree(again.getResponse().getContentAsString()).size());

        // ---- 13. the audit chain --------------------------------------------------
        assertTrue(audit.verifyChain().intact(), "the audit chain must verify end to end");

        JsonNode head = getJson("/api/public/audit/head");
        assertEquals(audit.headHash(), head.get("headHash").asText());
        assertTrue(head.get("eventCount").asLong() > 20);

        JsonNode trail = getJson("/api/audit/trail/DRAW/" + DRAW, REGISTRAR, "SCHEME_ADMIN");
        List<String> actions = new ArrayList<>();
        trail.forEach(e -> actions.add(e.get("action").asText()));
        assertTrue(actions.contains("SEED_COMMITTED"));
        assertTrue(actions.contains("DRAW_EXECUTED"));
        assertTrue(actions.contains("RESULT_PUBLISHED"));
        assertTrue(actions.contains("UNITS_ASSIGNED"));

        // An auditor reads; an auditor does not write.
        mvc.perform(actor(post("/api/admin/draws/" + DRAW + "/publish"), "auditor@state", "AUDITOR"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/verify")
                        .header(CallerActor.ID_HEADER, "auditor@state")
                        .header(CallerActor.ROLE_HEADER, "AUDITOR"))
                .andExpect(status().isOk());

        // And an unauthenticated caller gets nowhere near the admin endpoints.
        mvc.perform(post("/api/admin/draws/" + DRAW + "/publish"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private Requests.PublishRuleSet ruleSet() {
        return new Requests.PublishRuleSet(RULE_SET, SCHEME, UNITS, "OPEN",
                List.of(new Requests.Quota("CAT_A", "Reserved category A", new BigDecimal("15.0")),
                        new Requests.Quota("CAT_C", "Reserved category C", new BigDecimal("27.0"))),
                List.of(new Requests.Quota("WOMEN", "Women applicants", new BigDecimal("30.0"))),
                dev.harshith.housing.core.model.Rules.ResidencyMode.PRIORITY_TIER, 3,
                dev.harshith.housing.core.model.Rules.LapsePolicy.LAPSE_TO_OPEN, 20,
                "https://example.gov/rules.pdf");
    }

    private static final String[] GIVEN = {
            "RAMESH", "LAKSHMI", "SURESH", "PRIYA", "ANIL", "MEENA", "VENKAT", "GEETHA",
            "MANOJ", "SHANTHI", "RAJESH", "KAVITA"
    };
    private static final String[] FAMILY = {
            "KUMAR", "REDDY", "SHARMA", "IYER", "NAIR", "GOWDA", "PATIL", "RAO", "SHETTY", "MURTHY"
    };
    private static final String[] STREETS = {
            "MAIN ROAD", "2ND CROSS", "TEMPLE STREET", "MARKET ROAD", "LAKE VIEW ROAD",
            "SCHOOL STREET", "STATION ROAD"
    };

    /**
     * One synthetic application. The names, birth dates and addresses are spread out
     * deliberately: a fixture where every applicant is "APPLICANT-n" with the same
     * birthday would land the whole population in one blocking bucket and fill the review
     * queue with near-identical pairs, which tests the fixture rather than the system.
     */
    private IntakeRequest intake(int i) {
        String category = switch (i % 4) {
            case 0 -> "CAT_A";
            case 1 -> "CAT_C";
            default -> "OPEN";
        };
        return new IntakeRequest(SCHEME, i % 3 == 0 ? Channel.PAPER_KEYED : Channel.ONLINE,
                Instant.parse("2026-04-10T06:00:00Z").plusSeconds(i * 600L),
                i % 3 == 0 ? "BATCH-1" : null,
                null,
                GIVEN[i % GIVEN.length] + " " + FAMILY[(i * 7) % FAMILY.length],
                GIVEN[(i * 5) % GIVEN.length] + " " + FAMILY[(i * 7) % FAMILY.length],
                String.format("50000000%04d", i),
                String.format("98000%05d", i),
                LocalDate.of(1960 + (i % 40), 1 + (i % 12), 1 + (i % 27)),
                (i * 3 % 180 + 1) + ", " + STREETS[i % STREETS.length] + ", GANDHI NAGAR",
                i % 2 == 0 ? "W-11" : "W-90",
                i % 2 == 0 ? 12 : 1,
                category,
                i % 3 == 0 ? Set.of("WOMEN") : Set.of(),
                List.of("TWO_BHK", "ONE_BHK"));
    }

    private static IntakeRequest withKey(IntakeRequest r, String key) {
        return new IntakeRequest(r.schemeCode(), r.channel(), r.submittedAt(), r.batchId(), key,
                r.fullName(), r.relativeName(), r.governmentId(), r.phone(), r.dateOfBirth(),
                r.addressLine(), r.wardCode(), r.residencyYears(), r.verticalCode(),
                r.horizontalCodes(), r.unitTypePreferences());
    }

    private static IntakeRequest withWard(IntakeRequest r, String ward) {
        return new IntakeRequest(r.schemeCode(), r.channel(), r.submittedAt(), r.batchId(),
                r.idempotencyKey(), r.fullName(), r.relativeName(), r.governmentId(), r.phone(),
                r.dateOfBirth(), r.addressLine(), ward, r.residencyYears(), r.verticalCode(),
                r.horizontalCodes(), r.unitTypePreferences());
    }

    private String submit(IntakeRequest request) throws Exception {
        MvcResult result = mvc.perform(actor(post("/api/applications"), CLERK, "DATA_ENTRY")
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("applicationId").asText();
    }

    private void advance(SchemePhase to) throws Exception {
        mvc.perform(admin(post("/api/admin/schemes/" + SCHEME + "/phase"), REGISTRAR)
                        .content(json.writeValueAsString(new Requests.AdvancePhase(to))))
                .andExpect(status().isOk());
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, String actorId, String role) throws Exception {
        return json.readTree(mvc.perform(get(path)
                        .header(CallerActor.ID_HEADER, actorId)
                        .header(CallerActor.ROLE_HEADER, role))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder, String actorId) {
        return actor(builder, actorId, "SCHEME_ADMIN");
    }

    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder builder,
                                                String actorId, String role) {
        return builder.contentType(MediaType.APPLICATION_JSON)
                .header(CallerActor.ID_HEADER, actorId)
                .header(CallerActor.ROLE_HEADER, role);
    }
}
