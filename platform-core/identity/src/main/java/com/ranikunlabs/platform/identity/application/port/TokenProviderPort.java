package com.ranikunlabs.platform.identity.application.port;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Provider-neutral token boundary used by Identity authentication use cases. */
public interface TokenProviderPort {

    String generateAccessToken(UUID accountId, UUID organizationId, String role, UUID publicId);

    String generateRefreshToken(UUID accountId);

    boolean validateToken(String token);

    UUID getAccountIdFromToken(String token);

    OffsetDateTime getRefreshTokenExpiryDate();

    Duration getRemainingValidity(String token);
}
