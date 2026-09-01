package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Carelog-compatible refresh request. */
public record TokenRefreshRequest(
        @NotBlank(message = "Refresh Token은 필수입니다.")
        String refreshToken
) {

    /** Keeps the raw refresh token out of logs and error output. */
    @Override
    public String toString() {
        return "TokenRefreshRequest[refreshToken=REDACTED]";
    }
}
