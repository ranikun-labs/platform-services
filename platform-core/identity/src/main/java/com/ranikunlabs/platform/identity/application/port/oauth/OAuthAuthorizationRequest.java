package com.ranikunlabs.platform.identity.application.port.oauth;

import java.net.URI;

/** Provider-adapter input for building an authorization URL. */
public record OAuthAuthorizationRequest(
        String provider,
        URI redirectUri,
        String state,
        String codeChallenge,
        String codeChallengeMethod,
        String nonce
) {
}
