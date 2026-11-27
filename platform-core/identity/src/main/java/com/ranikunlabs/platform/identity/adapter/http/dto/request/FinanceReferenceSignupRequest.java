package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FinanceReferenceSignupRequest(
        @NotBlank String email,
        @NotBlank String password
) {

    @Override
    public String toString() {
        return "FinanceReferenceSignupRequest[email=REDACTED, password=REDACTED]";
    }
}
