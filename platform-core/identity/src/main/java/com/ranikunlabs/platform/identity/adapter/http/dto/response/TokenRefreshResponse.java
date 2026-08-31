package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Canonical Carelog refresh-token response. */
public record TokenRefreshResponse(
        String accessToken,
        String refreshToken
) {
}
