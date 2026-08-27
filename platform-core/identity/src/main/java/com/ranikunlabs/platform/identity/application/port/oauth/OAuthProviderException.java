package com.ranikunlabs.platform.identity.application.port.oauth;

/** Provider authentication failure that can be mapped to a typed OAuth result. */
public class OAuthProviderException extends RuntimeException {

    private final OAuthLoginResult.FailureReason reason;

    public OAuthProviderException(OAuthLoginResult.FailureReason reason) {
        this.reason = reason;
    }

    public OAuthProviderException(OAuthLoginResult.FailureReason reason, Throwable cause) {
        super(cause);
        this.reason = reason;
    }

    public OAuthLoginResult.FailureReason reason() {
        return reason;
    }
}
