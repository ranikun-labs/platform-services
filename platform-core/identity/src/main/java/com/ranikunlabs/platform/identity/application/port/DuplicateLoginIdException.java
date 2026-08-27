package com.ranikunlabs.platform.identity.application.port;

/** Duplicate login identifier during password-account registration. */
public final class DuplicateLoginIdException extends RuntimeException {

    public DuplicateLoginIdException() {
        super();
    }
}
