package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Carelog-compatible refresh response. */
public record TokenRefreshResponse(
        String accessToken,
        String refreshToken
) {

    /** Keeps rotated tokens out of logs and error output. */
    @Override
    public String toString() {
        return "TokenRefreshResponse[accessToken=REDACTED, refreshToken=REDACTED]";
    }
}
