package com.ranikunlabs.platform.identity.application.port.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 S256 PKCE value; plain mode is not supported. */
public record PkceChallenge(
        String codeVerifier,
        String codeChallenge,
        String method
) {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static PkceChallenge generate() {
        byte[] verifierBytes = new byte[32];
        SECURE_RANDOM.nextBytes(verifierBytes);
        String verifier = BASE64_URL_ENCODER.encodeToString(verifierBytes);
        return fromCodeVerifier(verifier);
    }

    /** Derives an S256 challenge from a caller-provided verifier. */
    public static PkceChallenge fromCodeVerifier(String codeVerifier) {
        return new PkceChallenge(codeVerifier, s256(codeVerifier), "S256");
    }

    private static String s256(String verifier) {
        try {
            return BASE64_URL_ENCODER.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}
