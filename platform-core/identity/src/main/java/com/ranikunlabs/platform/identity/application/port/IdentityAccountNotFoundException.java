package com.ranikunlabs.platform.identity.application.port;

/** The account credential targeted by a password update does not exist. */
public final class IdentityAccountNotFoundException extends RuntimeException {

    public IdentityAccountNotFoundException() {
        super();
    }
}
