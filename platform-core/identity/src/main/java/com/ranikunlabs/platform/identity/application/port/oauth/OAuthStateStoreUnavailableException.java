package com.ranikunlabs.platform.identity.application.port.oauth;

/** Distinguishes OAuth state-store outages from an invalid or expired state. */
public class OAuthStateStoreUnavailableException extends RuntimeException {

    public OAuthStateStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
