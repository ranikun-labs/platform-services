package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtAccessTokenIssuer;
import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtProperties;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceReferenceJwtAccessTokenIssuerTest {

    private static final String KEY = "Finance-Reference-Test-Only-Public-Dummy-Key-2026";

    @Test
    void issuesShortLivedAccessOnlyJwtWithReferencePurposeAndNoSessionClaims() {
        Instant now = Instant.parse("2026-11-06T13:00:00Z");
        FinanceReferenceJwtAccessTokenIssuer issuer = new FinanceReferenceJwtAccessTokenIssuer(
                new FinanceReferenceJwtProperties(KEY,
                        FinanceReferenceConfigurationGuard.ISSUER,
                        FinanceReferenceConfigurationGuard.AUDIENCE,
                        FinanceReferenceConfigurationGuard.ACCESS_TTL_SECONDS),
                Clock.fixed(now, ZoneOffset.UTC));

        IssuedFinanceReferenceAccessToken issued = issuer.issue("opaque-account-subject");
        String rawPayload = new String(
                Base64.getUrlDecoder().decode(issued.value().split("\\.")[1]), StandardCharsets.UTF_8);
        var payload = Jwts.parser()
                .clock(() -> Date.from(now.plusSeconds(1)))
                .verifyWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(issued.value()).getPayload();

        assertThat(payload.getIssuer()).isEqualTo(FinanceReferenceConfigurationGuard.ISSUER);
        assertThat(payload.getAudience()).containsExactly(FinanceReferenceConfigurationGuard.AUDIENCE);
        assertThat(rawPayload)
                .contains("\"aud\":\"" + FinanceReferenceConfigurationGuard.AUDIENCE + "\"")
                .doesNotContain("\"aud\":[");
        assertThat(payload.getSubject()).isEqualTo("opaque-account-subject");
        assertThat(payload.get("token_use", String.class)).isEqualTo("access");
        assertThat(payload.getIssuedAt()).isEqualTo(Date.from(now));
        assertThat(payload.getExpiration()).isEqualTo(Date.from(now.plusSeconds(300)));
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(payload.keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "iat", "exp", "token_use");
    }
}
