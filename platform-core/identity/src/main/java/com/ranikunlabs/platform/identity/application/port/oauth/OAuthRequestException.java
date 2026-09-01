package com.ranikunlabs.platform.identity.application.port.oauth;

import java.util.Objects;

/**
 * Typed, provider-neutral failure for an invalid OAuth authorization request.
 *
 * <p>The application layer uses this value instead of exposing web exception or provider
 * framework types. The Identity HTTP adapter maps the reason to the established Auth/OAuth
 * error response.</p>
 */
public final class OAuthRequestException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_OAUTH_PROVIDER,
        UNSUPPORTED_CLIENT_CHANNEL,
        INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING,
        INVALID_OAUTH_RETURN_TO
    }

    private final Reason reason;

    public OAuthRequestException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
