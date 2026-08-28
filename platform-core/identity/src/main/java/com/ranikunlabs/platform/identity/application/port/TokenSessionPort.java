package com.ranikunlabs.platform.identity.application.port;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Boundary for storing, rotating, and deleting refresh-token sessions. */
public interface TokenSessionPort {

    void replaceForAccount(UUID accountId, String newToken, OffsetDateTime expiresAt);

    Optional<RefreshSession> findByToken(String rawToken);

    void rotate(RefreshSession session, String newToken, OffsetDateTime newExpiresAt);

    void deleteForAccount(UUID accountId);

    void delete(RefreshSession session);
}
