package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Canonical Carelog Kakao authorization-start request. */
public record KakaoAuthorizationRequest(
        @NotNull(message = "OAuth 클라이언트 채널은 필수입니다.")
        ClientChannel clientChannel,
        @NotBlank(message = "로그인 완료 후 이동 경로는 필수입니다.")
        String returnTo
) {
}
