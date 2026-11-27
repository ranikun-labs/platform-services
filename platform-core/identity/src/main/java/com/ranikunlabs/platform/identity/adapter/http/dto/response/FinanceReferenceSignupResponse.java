package com.ranikunlabs.platform.identity.adapter.http.dto.response;

/** Public signup result. Password material and hashes are never part of the response. */
public record FinanceReferenceSignupResponse(String accountId, String email) {
}
