package com.ranikunlabs.platform.identity.adapter.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Required only when the isolated finance-reference profile is enabled. */
@ConfigurationProperties(prefix = "platform.identity.finance-reference.jwt")
public record FinanceReferenceJwtProperties(
        String signingKey,
        String issuer,
        String audience,
        Integer accessTokenTtlSeconds
) {

    @Override
    public String toString() {
        return "FinanceReferenceJwtProperties[signingKey=REDACTED, issuer=" + issuer
                + ", audience=" + audience + ", accessTokenTtlSeconds=" + accessTokenTtlSeconds + "]";
    }
}
