package com.ranikunlabs.platform.identity.application.port;

import java.util.Objects;

/** Framework-neutral failure meanings for refresh-token lifecycle operations. */
public final class TokenLifecycleException extends RuntimeException {

    public enum Reason {
        INVALID_REFRESH_TOKEN,
        REFRESH_TOKEN_NOT_FOUND,
        REFRESH_TOKEN_EXPIRED
    }

    private final Reason reason;

    public TokenLifecycleException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
