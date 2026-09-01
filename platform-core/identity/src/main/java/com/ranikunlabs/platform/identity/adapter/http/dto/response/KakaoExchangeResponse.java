package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Carelog-compatible Kakao login success response. */
public record KakaoExchangeResponse(
        String accessToken,
        String refreshToken
) {

    /** Keeps issued tokens out of logs and error output. */
    @Override
    public String toString() {
        return "KakaoExchangeResponse[accessToken=REDACTED, refreshToken=REDACTED]";
    }
}
