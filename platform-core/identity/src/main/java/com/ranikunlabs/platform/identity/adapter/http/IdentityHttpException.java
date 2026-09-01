package com.ranikunlabs.platform.identity.adapter.http;

import java.util.Objects;

/** Carries an Identity Auth/OAuth error status from the HTTP adapter to the exception handler. */
public final class IdentityHttpException extends RuntimeException {

    private final IdentityErrorStatus errorStatus;

    public IdentityHttpException(IdentityErrorStatus errorStatus) {
        this.errorStatus = Objects.requireNonNull(errorStatus, "errorStatus");
    }

    public IdentityErrorStatus errorStatus() {
        return errorStatus;
    }
}
