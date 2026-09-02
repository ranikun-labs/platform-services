package com.ranikunlabs.platform.identity.application.port;

import java.util.Objects;

/** Framework-neutral failure meanings for the product claims projection boundary. */
public final class CRMIdentityProjectionException extends RuntimeException {

    public enum Reason {
        PRODUCT_PROFILE_NOT_FOUND,
        PRODUCT_ROLE_NOT_ELIGIBLE,
        DEPENDENCY_AUTH_FAILURE,
        DEPENDENCY_UNAVAILABLE,
        DEPENDENCY_CONTRACT_FAILURE
    }

    private final Reason reason;

    public CRMIdentityProjectionException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
