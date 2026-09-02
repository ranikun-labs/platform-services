package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kakao token response, deliberately narrow.
 *
 * <p>Only the access token and its lifetime are modelled; Kakao's refresh token is not stored or
 * mapped, because Identity issues and rotates its own session credentials.
 */
record KakaoTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn
) {

    /** The access token is a provider credential and must never reach logs or messages. */
    @Override
    public String toString() {
        return "KakaoTokenResponse[accessToken=REDACTED, expiresIn=" + expiresIn + "]";
    }
}
