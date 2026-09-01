package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Carelog-compatible login response. */
public record LoginResponse(
        String accessToken,
        String refreshToken
) {

    /** Keeps issued tokens out of logs and error output. */
    @Override
    public String toString() {
        return "LoginResponse[accessToken=REDACTED, refreshToken=REDACTED]";
    }
}
