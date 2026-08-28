package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/** Boundary for changing a platform account password by stable account ID. */
public interface PasswordCredentialUpdatePort {

    /**
     * Updates the password credential for the given account ID.
     *
     * <p>A missing credential throws {@link IdentityAccountNotFoundException}; this is the
     * framework-neutral equivalent of the current {@code USER_NOT_FOUND} mapping.
     */
    UpdatedPasswordCredential updatePassword(UUID accountId, String rawPassword)
            throws IdentityAccountNotFoundException;
}
