package com.ranikunlabs.platform.identity.application.port;

/**
 * Authentication failed without disclosing whether the login identifier exists or the password
 * was incorrect.
 */
public final class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super();
    }
}
