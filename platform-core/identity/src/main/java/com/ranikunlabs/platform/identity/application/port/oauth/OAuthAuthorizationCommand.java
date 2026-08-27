package com.ranikunlabs.platform.identity.application.port.oauth;

/** Input for starting an OAuth authorization flow. */
public record OAuthAuthorizationCommand(
        String provider,
        ClientChannel clientChannel,
        String returnTo,
        String clientId
) {

    /** Compatibility constructor for the existing channel-based public API. */
    public OAuthAuthorizationCommand(String provider, ClientChannel clientChannel, String returnTo) {
        this(provider, clientChannel, returnTo, null);
    }
}
