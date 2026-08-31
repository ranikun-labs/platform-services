package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Canonical Carelog password-login response. */
public record LoginResponse(
        String accessToken,
        String refreshToken
) {
}
