package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Carelog-compatible login request; field names are part of the public wire contract. */
public record LoginRequest(
        @NotBlank(message = "사용자 ID는 필수입니다.")
        String userId,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {

    /** Keeps the submitted password out of logs and error output. */
    @Override
    public String toString() {
        return "LoginRequest[userId=" + userId + ", password=REDACTED]";
    }
}
