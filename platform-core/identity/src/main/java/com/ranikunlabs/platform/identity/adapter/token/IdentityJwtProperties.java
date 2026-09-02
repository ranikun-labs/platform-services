package com.ranikunlabs.platform.identity.adapter.token;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Compatibility binding for the canonical Carelog JWT configuration keys.
 *
 * <p>Bound only when the Identity Auth runtime is enabled, so foundation mode still boots without a
 * signing secret. When it is enabled, a missing or too-short secret must stop startup rather than
 * surface later as a runtime signing failure.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record IdentityJwtProperties(
        @NotBlank String secretKey,
        @Positive long accessTokenValidity,
        @Positive long refreshTokenValidity
) {

    /** Never rendered into logs; the signing secret is a credential. */
    @Override
    public String toString() {
        return "IdentityJwtProperties[secretKey=REDACTED"
                + ", accessTokenValidity=" + accessTokenValidity
                + ", refreshTokenValidity=" + refreshTokenValidity + "]";
    }
}
