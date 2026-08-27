package com.ranikunlabs.platform.identity.application.port;

/** Access and refresh tokens issued together after successful authentication. */
public record AuthTokenBundle(
        String accessToken,
        String refreshToken
) {
}
