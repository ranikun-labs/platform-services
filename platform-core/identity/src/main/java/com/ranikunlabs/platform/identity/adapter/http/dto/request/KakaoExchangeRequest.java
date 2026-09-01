package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Carelog-compatible Kakao authorization-code exchange request. */
public record KakaoExchangeRequest(
        @NotBlank(message = "인가 코드는 필수입니다.")
        String code,

        @NotBlank(message = "state는 필수입니다")
        String state
) {

    /** Keeps the authorization code and state out of logs and error output. */
    @Override
    public String toString() {
        return "KakaoExchangeRequest[code=REDACTED, state=REDACTED]";
    }
}
