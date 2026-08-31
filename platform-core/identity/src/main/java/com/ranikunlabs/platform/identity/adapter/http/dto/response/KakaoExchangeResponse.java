package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Canonical Carelog Kakao code-exchange response. */
public record KakaoExchangeResponse(
        String accessToken,
        String refreshToken
) {
}
