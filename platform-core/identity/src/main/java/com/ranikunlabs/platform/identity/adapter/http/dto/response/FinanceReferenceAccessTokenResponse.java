package com.ranikunlabs.platform.identity.adapter.http.dto.response;

public record FinanceReferenceAccessTokenResponse(
        String accessToken,
        String tokenType,
        String expiresAt
) {

    @Override
    public String toString() {
        return "FinanceReferenceAccessTokenResponse[accessToken=REDACTED, tokenType="
                + tokenType + ", expiresAt=" + expiresAt + "]";
    }
}
