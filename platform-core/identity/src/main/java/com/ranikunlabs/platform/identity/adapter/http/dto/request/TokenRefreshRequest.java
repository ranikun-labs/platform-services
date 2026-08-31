package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Canonical Carelog refresh-token request. */
public record TokenRefreshRequest(
        @NotBlank(message = "Refresh Token은 필수입니다.")
        String refreshToken
) {

    @Override
    public String toString() {
        return "TokenRefreshRequest[refreshToken=REDACTED]";
    }
}
