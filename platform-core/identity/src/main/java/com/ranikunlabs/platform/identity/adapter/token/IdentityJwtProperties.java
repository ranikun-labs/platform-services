package com.ranikunlabs.platform.identity.adapter.token;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Compatibility binding for the canonical Carelog JWT configuration keys. */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record IdentityJwtProperties(
        @NotBlank String secretKey,
        @Min(1) long accessTokenValidity,
        @Min(1) long refreshTokenValidity
) {
}
