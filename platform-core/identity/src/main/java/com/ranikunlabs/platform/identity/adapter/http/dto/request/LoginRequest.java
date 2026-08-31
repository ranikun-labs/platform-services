package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Canonical Carelog password-login request. */
public record LoginRequest(
        @NotBlank(message = "사용자 ID는 필수입니다.")
        String userId,
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {

    @Override
    public String toString() {
        return "LoginRequest[userId=" + userId + ", password=REDACTED]";
    }
}
