package dev.harshith.housing.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * All hashing in this system is SHA-256 over UTF-8 bytes, rendered as lowercase hex.
 *
 * <p>Why this matters: every hash produced here is meant to be recomputable by a third
 * party (an applicant, a journalist, a court-appointed expert) using nothing more than
 * {@code sha256sum} and the published artefacts. So there is exactly one hash function,
 * one encoding, and one rendering, and none of them are configurable.
 */
public final class Hashing {

    /** Predecessor hash of the first link in any hash chain. */
    public static final String GENESIS = "0".repeat(64);

    private Hashing() {
    }

    public static String sha256Hex(String input) {
        return toHex(digest().digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Chains a payload onto a previous hash: {@code H(prev + "|" + payload)}.
     * Used by the audit log and by the seed derivation, both of which need the
     * ordering of inputs to be part of what is signed.
     */
    public static String chain(String previousHex, String payload) {
        return sha256Hex(previousHex + "|" + payload);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
