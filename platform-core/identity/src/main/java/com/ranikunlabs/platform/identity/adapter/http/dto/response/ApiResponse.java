package com.ranikunlabs.platform.identity.adapter.http.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Minimal response envelope matching the canonical Carelog auth contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T> {

    private static final String SUCCESS_MESSAGE = "요청에 성공하였습니다.";

    private final int status;
    private final String message;
    private final T data;

    private ApiResponse(HttpStatus status, String message, T data) {
        this.status = status.value();
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> of(HttpStatus status, String message) {
        return new ApiResponse<>(status, message, null);
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(new ApiResponse<>(HttpStatus.OK, SUCCESS_MESSAGE, data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(of(status, message));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(HttpStatus.CREATED, SUCCESS_MESSAGE, data));
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
