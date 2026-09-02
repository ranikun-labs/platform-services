package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET =
            "slice-c-test-secret-key-long-enough-for-hmac-signing-0123456789";
    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PUBLIC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Clock clock;
    private JwtTokenProvider provider;
    private SecretKey verificationKey;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
        provider = new JwtTokenProvider(SECRET, Duration.ofMinutes(15).toMillis(),
                Duration.ofDays(14).toMillis(), clock);
        verificationKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void accessTokenPreservesCanonicalClaimsAndTtl() {
        String token = provider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        Claims claims = parse(token);

        assertThat(claims.getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(claims.get("organizationId", String.class)).isEqualTo(ORGANIZATION_ID.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("MANAGER");
        assertThat(claims.get("publicId", String.class)).isEqualTo(PUBLIC_ID.toString());
        assertThat(claims.get("managerType")).isNull();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(Duration.ofMinutes(15).toMillis());
    }

    @Test
    void refreshTokenContainsOnlyAccountSubjectAndCanonicalTtl() {
        String token = provider.generateRefreshToken(ACCOUNT_ID);
        Claims claims = parse(token);

        assertThat(claims.getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(claims.get("organizationId")).isNull();
        assertThat(claims.get("role")).isNull();
        assertThat(claims.get("publicId")).isNull();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(Duration.ofDays(14).toMillis());
        assertThat(provider.getAccountIdFromToken(token)).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void validationAndRemainingValidityUseProviderClockAndSigningKey() {
        String token = provider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.validateToken(token + "tampered")).isFalse();
        assertThat(provider.getRemainingValidity(token)).isEqualTo(Duration.ofMinutes(15));
        assertThat(provider.getRefreshTokenExpiryDate())
                .isEqualTo(Instant.now(clock).plus(Duration.ofDays(14)).atOffset(ZoneOffset.UTC));
    }

    @Test
    void accessTokenValidationAcceptsOnlyTokensCarryingTheAccessClaimSet() {
        String accessToken = provider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        String refreshToken = provider.generateRefreshToken(ACCOUNT_ID);

        assertThat(provider.validateAccessToken(accessToken)).isTrue();
        // Authentic and unexpired, but not an access credential.
        assertThat(provider.validateToken(refreshToken)).isTrue();
        assertThat(provider.validateAccessToken(refreshToken)).isFalse();
        assertThat(provider.validateAccessToken(accessToken + "tampered")).isFalse();
    }

    @Test
    void accessTokenValidationRejectsMissingOrUnparsableAccessClaims() {
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), null, "MANAGER", PUBLIC_ID.toString()))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), "not-a-uuid", "MANAGER", PUBLIC_ID.toString()))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), ORGANIZATION_ID.toString(), null, PUBLIC_ID.toString()))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), ORGANIZATION_ID.toString(), "  ", PUBLIC_ID.toString()))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), ORGANIZATION_ID.toString(), "MANAGER", null))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken(ACCOUNT_ID.toString(), ORGANIZATION_ID.toString(), "MANAGER", "not-a-uuid"))).isFalse();
        assertThat(provider.validateAccessToken(
                signedToken("not-a-uuid", ORGANIZATION_ID.toString(), "MANAGER", PUBLIC_ID.toString()))).isFalse();
    }

    /** Builds an otherwise-authentic token with a chosen access-claim shape. */
    private String signedToken(String subject, String organizationId, String role, String publicId) {
        Instant now = Instant.now(clock);
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))));
        if (organizationId != null) {
            builder.claim("organizationId", organizationId);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        if (publicId != null) {
            builder.claim("publicId", publicId);
        }
        return builder.signWith(verificationKey).compact();
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(verificationKey).build().parseSignedClaims(token).getPayload();
    }
}
