package com.ranikunlabs.platform.identity.adapter.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Identity-owned equivalent of the shared API envelope.
 *
 * <p>Identity does not depend on Carelog response classes, but the JSON shape
 * ({@code status}, {@code message}, {@code data}) and the success message are part of the
 * public wire contract and must stay byte-compatible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdentityApiResponse<T>(int status, String message, T data) {

    private static final String SUCCESS_MESSAGE = "요청에 성공하였습니다.";

    public static <T> IdentityApiResponse<T> of(HttpStatus status, String message) {
        return new IdentityApiResponse<>(status.value(), message, null);
    }

    public static <T> IdentityApiResponse<T> of(HttpStatus status, String message, T data) {
        return new IdentityApiResponse<>(status.value(), message, data);
    }

    public static <T> ResponseEntity<IdentityApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(new IdentityApiResponse<>(HttpStatus.OK.value(), SUCCESS_MESSAGE, data));
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }
}
