package com.ranikunlabs.platform.identity.application.port.oauth;

/** Verified external identity without exposing provider SDK types. */
public record OAuthPrincipal(
        String provider,
        String providerSubject,
        String email,
        boolean emailVerified,
        String displayName
) {
}
