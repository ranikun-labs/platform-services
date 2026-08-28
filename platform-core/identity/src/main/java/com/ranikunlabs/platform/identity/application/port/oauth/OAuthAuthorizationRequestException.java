package com.ranikunlabs.platform.identity.application.port.oauth;

import java.util.Objects;

/**
 * Typed failure for OAuth authorization/callback request validation that fails before any
 * provider or state-store interaction (unsupported provider, client/channel mismatch, unsupported
 * redirect combination, invalid returnTo).
 */
public final class OAuthAuthorizationRequestException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_OAUTH_PROVIDER,
        INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING,
        UNSUPPORTED_CLIENT_CHANNEL,
        INVALID_OAUTH_RETURN_TO
    }

    private final Reason reason;

    public OAuthAuthorizationRequestException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
