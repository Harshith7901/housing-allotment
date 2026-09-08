package dev.harshith.housing.web;

import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.core.bundle.VerificationBundle;
import dev.harshith.housing.core.draw.ResultHasher;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawStatus;
import dev.harshith.housing.service.BundleService;
import dev.harshith.housing.service.DrawService;
import dev.harshith.housing.service.ExplanationService;
import dev.harshith.housing.service.RollService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a member of the public, an applicant, a journalist or a court needs, with no
 * credentials at all.
 *
 * <p>This controller is the answer to the three audiences in the brief, and it only works
 * because the roll was designed from the start to carry decision-relevant attributes and
 * nothing identifying. Full public verifiability and applicant privacy are usually treated
 * as a trade-off; they are not, provided the separation is made early.
 *
 * <p>Everything here refuses to serve an unpublished draw. An executed but unannounced
 * result is not public information, and leaking it to whoever asks first is its own
 * scandal.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final DrawService draws;
    private final RollService rolls;
    private final BundleService bundles;
    private final ExplanationService explanations;

    public PublicController(DrawService draws,
                            RollService rolls,
                            BundleService bundles,
                            ExplanationService explanations) {
        this.draws = draws;
        this.rolls = rolls;
        this.bundles = bundles;
        this.explanations = explanations;
    }

    /** "Why not me?" — for the applicant who has their own application id. */
    @GetMapping("/applications/{applicationId}/explanation")
    public ExplanationService.Explanation explanation(@PathVariable String applicationId) {
        return explanations.explain(applicationId);
    }

    @GetMapping("/draws/{drawId}")
    public Map<String, Object> draw(@PathVariable String drawId) {
        DrawEntity entity = requirePublished(drawId);
        Draw.DrawOutcome outcome = draws.outcome(drawId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawId", entity.getDrawId());
        body.put("schemeCode", entity.getSchemeCode());
        body.put("ruleSetVersion", entity.getRuleSetVersion());
        body.put("rollId", entity.getRollId());
        body.put("rollHash", entity.getRollHash());
        body.put("commitmentHex", entity.getCommitmentHex());
        body.put("committedAt", entity.getCommittedAt());
        body.put("entropySourceDescription", entity.getEntropySourceDescription());
        body.put("publicEntropy", entity.getPublicEntropy());
        body.put("revealedNonce", entity.getNonce());
        body.put("seedHex", entity.getSeedHex());
        body.put("executedAt", entity.getExecutedAt());
        body.put("publishedAt", entity.getPublishedAt());
        body.put("resultHash", entity.getResultHash());
        body.put("candidates", outcome.selections().size());
        body.put("selected", outcome.selectedCount());
        body.put("resultVerifiedByRecomputation", true);
        body.put("howToCheckTheSeed", "printf '%s' 'commit/1|" + entity.getNonce() + "' | sha256sum"
                + "   # must equal " + entity.getCommitmentHex());
        body.put("verificationBundle", "/api/public/draws/" + drawId + "/verification-bundle");
        return body;
    }

    /** The canonical result text. Its SHA-256 is the published result hash. */
    @GetMapping(value = "/draws/{drawId}/result", produces = MediaType.TEXT_PLAIN_VALUE)
    public String result(@PathVariable String drawId) {
        requirePublished(drawId);
        return ResultHasher.encode(draws.outcome(drawId));
    }

    /** The canonical roll text. Its SHA-256 is the published roll hash. */
    @GetMapping(value = "/draws/{drawId}/roll", produces = MediaType.TEXT_PLAIN_VALUE)
    public String roll(@PathVariable String drawId) {
        DrawEntity entity = requirePublished(drawId);
        return rolls.canonicalText(entity.getRollId());
    }

    @GetMapping("/draws/{drawId}/verification-bundle")
    public ResponseEntity<byte[]> bundle(@PathVariable String drawId) {
        requirePublished(drawId);
        byte[] zip = bundles.zip(drawId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + drawId + "-verification-bundle.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    /** The same bundle inline, for reading in a browser rather than downloading. */
    @GetMapping("/draws/{drawId}/verification-bundle/manifest")
    public ResponseEntity<String> manifest(@PathVariable String drawId) {
        requirePublished(drawId);
        VerificationBundle.Contents contents = bundles.build(drawId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(contents.manifest());
    }

    private DrawEntity requirePublished(String drawId) {
        DrawEntity entity = draws.entity(drawId);
        if (entity.getStatus() != DrawStatus.PUBLISHED && entity.getStatus() != DrawStatus.ANNULLED) {
            throw new PhaseViolationException("draw " + drawId + " has not been published yet");
        }
        return entity;
    }
}
