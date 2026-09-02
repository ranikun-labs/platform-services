package com.ranikunlabs.platform.identity.adapter.token;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

/** JJWT adapter preserving the current symmetric JWT claims and lifetime semantics. */
public class JwtTokenProvider implements TokenProviderPort {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey secretKey;
    private final long accessTokenValidity;
    private final long refreshTokenValidity;
    private final Clock clock;

    public JwtTokenProvider(
            String secretKey,
            long accessTokenValidity,
            long refreshTokenValidity,
            Clock clock
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidity = accessTokenValidity;
        this.refreshTokenValidity = refreshTokenValidity;
        this.clock = clock;
    }

    @Override
    public String generateAccessToken(UUID accountId, UUID organizationId, String role, UUID publicId) {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .subject(accountId.toString())
                .claim("organizationId", organizationId.toString())
                .claim("role", role)
                .claim("publicId", publicId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenValidity)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Issues a refresh token that is unique per issuance.
     *
     * <p>{@code sub}, {@code iat}, and {@code exp} alone do not distinguish two tokens minted for
     * one account within the same second, because JWT time claims have one-second wire resolution.
     * A rotation that produced the previous token byte-for-byte would leave the consumed credential
     * usable, so each issuance carries a random {@code jti}. The claim is additive: it is never
     * required on presentation, so refresh tokens issued before this change stay usable.
     */
    @Override
    public String generateRefreshToken(UUID accountId) {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .subject(accountId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshTokenValidity)))
                .signWith(secretKey)
                .compact();
    }

    @Override
    public UUID getAccountIdFromToken(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SecurityException | MalformedJwtException | SignatureException exception) {
            log.error("Invalid JWT signature: {}", exception.getMessage());
        } catch (ExpiredJwtException exception) {
            log.error("Expired JWT token: {}", exception.getMessage());
        } catch (UnsupportedJwtException exception) {
            log.error("Unsupported JWT token: {}", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            log.error("JWT claims string is empty: {}", exception.getMessage());
        }
        return false;
    }

    /**
     * Distinguishes an access token from any other authentic token by its claim shape.
     *
     * <p>The issued JWT format is unchanged: access tokens already carry {@code organizationId},
     * {@code role}, and {@code publicId}, and refresh tokens carry none of them, so requiring those
     * claims separates the two token classes without adding a type claim.
     */
    @Override
    public boolean validateAccessToken(String token) {
        if (!validateToken(token)) {
            return false;
        }
        try {
            Claims claims = getClaims(token);
            UUID.fromString(claims.getSubject());
            UUID.fromString(claims.get("organizationId", String.class));
            UUID.fromString(claims.get("publicId", String.class));
            String role = claims.get("role", String.class);
            return role != null && !role.isBlank();
        } catch (RuntimeException exception) {
            // A missing or unparsable access claim means this is not a usable access credential.
            log.warn("Rejected bearer token without a complete access-token claim set");
            return false;
        }
    }

    @Override
    public OffsetDateTime getRefreshTokenExpiryDate() {
        return OffsetDateTime.now(clock).plus(Duration.ofMillis(refreshTokenValidity));
    }

    public UUID getOrganizationIdFromToken(String token) {
        return UUID.fromString(getClaims(token).get("organizationId", String.class));
    }

    public String getRoleFromToken(String token) {
        return getClaims(token).get("role", String.class);
    }

    public UUID getPublicIdFromToken(String token) {
        return UUID.fromString(getClaims(token).get("publicId", String.class));
    }

    @Override
    public Duration getRemainingValidity(String token) {
        Date expiration = getClaims(token).getExpiration();
        long remaining = expiration.getTime() - Instant.now(clock).toEpochMilli();
        return Duration.ofMillis(Math.max(remaining, 0));
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
