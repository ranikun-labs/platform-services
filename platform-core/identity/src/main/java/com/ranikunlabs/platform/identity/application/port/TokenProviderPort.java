package com.ranikunlabs.platform.identity.application.port;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Provider-neutral token boundary used by Identity authentication use cases. */
public interface TokenProviderPort {

    String generateAccessToken(UUID accountId, UUID organizationId, String role, UUID publicId);

    String generateRefreshToken(UUID accountId);

    boolean validateToken(String token);

    /**
     * Verifies that a token is usable as an access credential, not merely a well-signed token.
     *
     * <p>{@link #validateToken(String)} answers only whether a token is authentic and unexpired,
     * which every issued token is — including refresh tokens. Bearer authentication must additionally
     * establish the token's class, so this operation also requires the claims that only an access
     * token carries. Refresh tokens must fail it.
     */
    boolean validateAccessToken(String token);

    UUID getAccountIdFromToken(String token);

    OffsetDateTime getRefreshTokenExpiryDate();

    Duration getRemainingValidity(String token);
}
