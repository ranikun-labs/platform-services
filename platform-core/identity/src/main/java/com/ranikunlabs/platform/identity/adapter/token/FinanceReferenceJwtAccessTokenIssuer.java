package com.ranikunlabs.platform.identity.adapter.token;

import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccessTokenPort;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/** Finance-only signer; it cannot issue a refresh token or mutate session state. */
public final class FinanceReferenceJwtAccessTokenIssuer implements FinanceReferenceAccessTokenPort {

    private final FinanceReferenceJwtProperties properties;
    private final SecretKey signingKey;
    private final Clock clock;

    public FinanceReferenceJwtAccessTokenIssuer(FinanceReferenceJwtProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signingKey = Keys.hmacShaKeyFor(properties.signingKey().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public IssuedFinanceReferenceAccessToken issue(String opaqueSubject) {
        if (opaqueSubject == null || opaqueSubject.isBlank()
                || opaqueSubject.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("Finance reference subject is invalid.");
        }
        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(properties.accessTokenTtlSeconds());
        String token = Jwts.builder()
                .subject(opaqueSubject)
                .issuer(properties.issuer())
                .audience().single(properties.audience())
                .claim("token_use", "access")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedFinanceReferenceAccessToken(token, expiresAt);
    }
}
