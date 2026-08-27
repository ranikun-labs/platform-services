package com.ranikunlabs.platform.identity.application.port.oauth;

import java.time.Instant;

/** Provider token response passed only between the provider and principal ports. */
public record OAuthTokenGrant(
        String accessToken,
        String idToken,
        Instant expiresAt
) {
}
