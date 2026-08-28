package com.ranikunlabs.platform.identity.application.port;

/** Typed boundary for password credential verification. */
public interface CredentialPort {

    /**
     * Verifies a login identifier and password.
     *
     * <p>Invalid credentials must retain the existing authentication failure mapping at the
     * application/API boundary. Product profile claims are intentionally resolved separately.
     * Missing login identifiers and password mismatches both throw the same
     * {@link InvalidCredentialsException}.
     */
    AuthenticatedIdentity authenticate(String loginId, String password) throws InvalidCredentialsException;
}
