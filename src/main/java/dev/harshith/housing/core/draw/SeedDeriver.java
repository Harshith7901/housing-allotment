package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.util.Hashing;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Commit–reveal seed protocol.
 *
 * <p>The hard problem in a public lottery is not generating randomness. It is convincing a
 * hostile audience that <em>nobody chose the outcome</em>. A seeded PRNG inside the
 * application solves nothing: whoever picks the seed picks the winners, and "we used
 * {@code SecureRandom}" is unfalsifiable after the fact.
 *
 * <p>So the seed is derived from three inputs, no one of which can steer the result:
 *
 * <ol>
 *   <li><b>The roll hash.</b> Fixes <em>who</em> is in the draw. The seed cannot be chosen
 *       to suit a roll that is later edited, because editing the roll changes the seed.</li>
 *   <li><b>A secret nonce, committed in advance.</b> The authority generates a nonce,
 *       publishes only {@code SHA-256(nonce)}, and reveals the nonce at draw time. It was
 *       fixed before the third input existed, so the authority could not tune it to a
 *       known outcome — and cannot swap it afterwards without breaking the commitment.</li>
 *   <li><b>A public entropy value the authority does not control</b>, fixed after the
 *       commitment is published: the winning number of a specified public lottery draw, a
 *       named stock index close, a specified blockchain block hash. Whatever is chosen,
 *       it is named in the scheme notification beforehand and it is verifiable by anyone
 *       later.</li>
 * </ol>
 *
 * <p>The authority cannot bias the outcome because it is committed before the entropy is
 * known. The entropy source cannot bias the outcome because it does not know the nonce.
 * And anyone holding the published bundle can recompute the seed in one line of shell.
 */
public final class SeedDeriver {

    /** Version tag inside the hashed material, so the protocol can be changed without ambiguity. */
    private static final String DOMAIN = "seed/1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SeedDeriver() {
    }

    /** Generates a fresh 256-bit nonce as hex. Kept secret until the draw is executed. */
    public static String generateNonce() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String commitmentOf(String nonce) {
        return Hashing.sha256Hex("commit/1|" + nonce);
    }

    public static Draw.SeedCommitment commit(String drawId,
                                             String rollHash,
                                             String nonce,
                                             Instant committedAt,
                                             String committedBy) {
        return new Draw.SeedCommitment(drawId, rollHash, commitmentOf(nonce), committedAt, committedBy);
    }

    public static boolean commitmentHolds(Draw.SeedCommitment commitment, String revealedNonce) {
        return commitment != null
                && commitment.commitmentHex() != null
                && commitment.commitmentHex().equals(commitmentOf(revealedNonce));
    }

    /**
     * @param publicEntropy the value from the pre-announced public source, recorded verbatim
     * @throws IllegalArgumentException if the revealed nonce does not match the commitment
     */
    public static Draw.DrawSeed reveal(Draw.SeedCommitment commitment,
                                       String publicEntropy,
                                       String nonce) {
        if (!commitmentHolds(commitment, nonce)) {
            throw new IllegalArgumentException(
                    "revealed nonce does not match the published commitment for draw " + commitment.drawId()
                            + " - the draw must not proceed");
        }
        if (publicEntropy == null || publicEntropy.isBlank()) {
            throw new IllegalArgumentException("public entropy value is required before a draw may be executed");
        }
        String material = DOMAIN + "|" + commitment.rollHash() + "|" + publicEntropy + "|" + nonce;
        return new Draw.DrawSeed(
                Hashing.sha256Hex(material),
                commitment.rollHash(),
                publicEntropy,
                nonce,
                "seedHex = SHA256(\"" + DOMAIN + "|<rollHash>|<publicEntropy>|<nonce>\")");
    }

    /** Recomputes the seed from published values; used by the verification bundle checker. */
    public static String recompute(String rollHash, String publicEntropy, String nonce) {
        return Hashing.sha256Hex(DOMAIN + "|" + rollHash + "|" + publicEntropy + "|" + nonce);
    }
}
