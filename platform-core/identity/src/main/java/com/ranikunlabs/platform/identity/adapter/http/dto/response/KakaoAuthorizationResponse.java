package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Carelog-compatible authorization-start response; only the authorization URL is exposed. */
public record KakaoAuthorizationResponse(
        String authorizationUrl
) {
}
