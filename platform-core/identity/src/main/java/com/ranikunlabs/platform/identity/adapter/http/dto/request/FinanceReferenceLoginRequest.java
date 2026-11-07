package com.ranikunlabs.platform.identity.adapter.http.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FinanceReferenceLoginRequest(
        @NotBlank String userId,
        @NotBlank String password
) {

    @Override
    public String toString() {
        return "FinanceReferenceLoginRequest[userId=REDACTED, password=REDACTED]";
    }
}
