package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/**
 * Result of registering a password-backed platform account without exposing an entity.
 *
 * <p>The encoded hash is retained only for the compatibility enrollment projection that mirrors
 * the legacy product password column. It is not a cross-service authentication API.
 */
public record IdentityAccount(
        UUID accountId,
        String loginId,
        String encodedPasswordHash
) {
}
