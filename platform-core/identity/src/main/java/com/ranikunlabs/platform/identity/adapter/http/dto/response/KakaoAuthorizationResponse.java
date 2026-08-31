package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Canonical Carelog Kakao authorization-start response. */
public record KakaoAuthorizationResponse(
        String authorizationUrl
) {
}
