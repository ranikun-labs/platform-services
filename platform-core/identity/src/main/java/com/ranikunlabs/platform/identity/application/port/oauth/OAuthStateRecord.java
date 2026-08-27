package com.ranikunlabs.platform.identity.application.port.oauth;

import java.net.URI;
import java.time.Instant;

/** Sensitive server-side state required to complete one OAuth callback. */
public record OAuthStateRecord(
        String provider,
        URI redirectUri,
        String returnTo,
        String codeVerifier,
        String nonce,
        Instant issuedAt
) {
}
