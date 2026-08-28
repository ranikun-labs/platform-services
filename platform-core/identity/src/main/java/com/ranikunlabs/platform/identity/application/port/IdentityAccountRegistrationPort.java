package com.ranikunlabs.platform.identity.application.port;

/** Boundary for registering a password-backed platform account. */
public interface IdentityAccountRegistrationPort {

    /**
     * Registers an account and its password credential.
     *
     * <p>Email uniqueness and product profile constraints remain caller-owned.
     * A duplicate login identifier throws {@link DuplicateLoginIdException}; this is the
     * framework-neutral equivalent of the current {@code DUPLICATE_USER_ID} mapping.
     */
    IdentityAccount registerPasswordAccount(String loginId, String email, String rawPassword)
            throws DuplicateLoginIdException;
}
