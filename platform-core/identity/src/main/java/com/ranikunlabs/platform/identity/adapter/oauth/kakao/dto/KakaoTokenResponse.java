package com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Minimal Kakao token response; provider refresh details do not cross the adapter boundary. */
public record KakaoTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn
) {
}
