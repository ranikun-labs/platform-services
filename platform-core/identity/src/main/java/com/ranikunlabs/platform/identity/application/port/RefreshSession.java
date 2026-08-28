package com.ranikunlabs.platform.identity.application.port;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal refresh-session value crossing the persistence port. */
public record RefreshSession(
        String tokenValue,
        UUID accountId,
        OffsetDateTime expiresAt
) {
}
