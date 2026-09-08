package dev.harshith.housing.service;

import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.core.bundle.VerificationBundle;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles the downloadable verification bundle for a published draw.
 *
 * <p>Available only once the draw is published, and always built from a live recomputation
 * rather than from stored text — {@link DrawService#outcome} reloads the roll and re-runs
 * the engine, so a bundle can never be handed out that disagrees with the announced
 * result hash.
 */
@Service
public class BundleService {

    private final DrawService draws;
    private final RollService rolls;
    private final RuleSetService ruleSets;
    private final AuditService audit;

    public BundleService(DrawService draws,
                         RollService rolls,
                         RuleSetService ruleSets,
                         AuditService audit) {
        this.draws = draws;
        this.rolls = rolls;
        this.ruleSets = ruleSets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public VerificationBundle.Contents build(String drawId) {
        DrawEntity entity = draws.entity(drawId);
        if (entity.getStatus() != DrawStatus.PUBLISHED) {
            throw new PhaseViolationException("draw " + drawId + " is " + entity.getStatus()
                    + "; a verification bundle is published with the result, not before it");
        }

        Rules.RuleSet rules = ruleSets.load(entity.getRuleSetVersion());
        Roll.DrawRoll roll = rolls.load(entity.getRollId());
        Draw.DrawOutcome outcome = draws.outcome(drawId);

        Draw.SeedCommitment commitment = new Draw.SeedCommitment(drawId, entity.getRollHash(),
                entity.getCommitmentHex(), entity.getCommittedAt(), entity.getCommittedBy());
        Draw.DrawSeed seed = new Draw.DrawSeed(entity.getSeedHex(), entity.getRollHash(),
                entity.getPublicEntropy(), entity.getNonce(),
                "seedHex = SHA256(\"seed/1|<rollHash>|<publicEntropy>|<nonce>\")");

        return VerificationBundle.build(rules, roll, commitment, seed, outcome, audit.headHash());
    }

    /** The same bundle as a zip, which is how a member of the public will want it. */
    @Transactional(readOnly = true)
    public byte[] zip(String drawId) {
        VerificationBundle.Contents contents = build(drawId);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : contents.files().entrySet()) {
                writeEntry(zip, file.getKey(), file.getValue());
            }
            writeEntry(zip, "MANIFEST.txt", contents.manifest());
            writeEntry(zip, "README.txt", readme(drawId));
        } catch (IOException e) {
            throw new IllegalStateException("could not assemble the verification bundle for " + drawId, e);
        }
        return buffer.toByteArray();
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        // A fixed timestamp keeps the zip byte-identical across downloads, so the archive
        // itself can be hashed and compared.
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String readme(String drawId) {
        return """
                Verification bundle for draw %s
                ================================================================

                This bundle contains every input to the allotment decision, in the exact
                bytes that were hashed and published. You do not have to trust the
                authority, this software, or anybody who handed you this file: you can
                recompute the result yourself and compare.

                Files
                -----
                  rules.txt     the published rule set. SHA-256 of this file is the
                                ruleSetHash quoted in the notification.
                  roll.txt      every application id in the draw, with the category,
                                quota flags and residency the draw acted on. No names,
                                phone numbers or identity numbers: the roll carries only
                                what the rules act on. SHA-256 of this file is the
                                rollHash.
                  seed.txt      the commitment published before the draw, the nonce
                                revealed at the draw, the public entropy value, and the
                                derived seed.
                  result.txt    the seat apportionment, the pools, and one line for every
                                applicant with their ticket, rank and outcome. SHA-256 of
                                this file is the resultHash.
                  MANIFEST.txt  all four hashes, the audit head hash, and per-file digests.

                Checking a single file
                ----------------------
                  sha256sum rules.txt roll.txt seed.txt result.txt
                  # compare against the values in MANIFEST.txt

                Checking your own ticket
                ------------------------
                  printf '%%s' 'ticket/1|<seedHex>|<yourApplicationId>' | sha256sum
                  # compare against the ticket on your line in result.txt

                Checking the seed was not chosen to suit the result
                --------------------------------------------------
                  printf '%%s' 'commit/1|<nonce>' | sha256sum
                  # must equal the commitment, which was published before the entropy
                  # value existed
                  printf '%%s' 'seed/1|<rollHash>|<publicEntropy>|<nonce>' | sha256sum
                  # must equal seedHex

                Checking the entire draw
                ------------------------
                  python3 verify.py <this directory>

                verify.py re-implements the published rules from scratch, in a different
                language, sharing no code with the service that produced this bundle. It
                recomputes every ticket, every ranking, the quota arithmetic and every
                applicant's outcome, and reports the first line on which its answer differs
                from the published one.
                """.formatted(drawId);
    }
}
