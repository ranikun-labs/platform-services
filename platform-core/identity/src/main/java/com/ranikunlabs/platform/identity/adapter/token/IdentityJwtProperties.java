package com.ranikunlabs.platform.identity.adapter.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Compatibility binding for the canonical Carelog JWT configuration keys. */
@ConfigurationProperties(prefix = "jwt")
public record IdentityJwtProperties(
        String secretKey,
        long accessTokenValidity,
        long refreshTokenValidity
) {
}
