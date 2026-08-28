package com.ranikunlabs.platform.identity.application.port.oauth;

/** Input for completing an OAuth callback; verifier and redirect URI stay server-side. */
public record OAuthCallbackCommand(
        String provider,
        String authorizationCode,
        String state
) {
}
