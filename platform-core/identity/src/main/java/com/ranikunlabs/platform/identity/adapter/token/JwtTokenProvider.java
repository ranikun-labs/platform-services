package com.ranikunlabs.platform.identity.adapter.token;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
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

    @Override
    public boolean validateAccessToken(String token) {
        try {
            Claims claims = getClaims(token);
            return isUuid(claims.getSubject())
                    && isUuid(claims.get("organizationId", String.class))
                    && isNonBlank(claims.get("role", String.class))
                    && isUuid(claims.get("publicId", String.class));
        } catch (SecurityException | JwtException | IllegalArgumentException exception) {
            log.error("Invalid access JWT: {}", exception.getMessage());
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

    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
