package com.ranikunlabs.platform.identity.adapter.token;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Compatibility binding for the canonical Carelog JWT configuration keys. */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record IdentityJwtProperties(
        @NotBlank String secretKey,
        @Positive long accessTokenValidity,
        @Positive long refreshTokenValidity
) {

    @Override
    public String toString() {
        return "IdentityJwtProperties[secretKey=REDACTED, accessTokenValidity="
                + accessTokenValidity + ", refreshTokenValidity=" + refreshTokenValidity + "]";
    }
}
