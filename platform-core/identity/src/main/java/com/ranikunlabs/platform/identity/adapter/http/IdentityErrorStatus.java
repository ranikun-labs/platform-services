package com.ranikunlabs.platform.identity.adapter.http;

import org.springframework.http.HttpStatus;

/**
 * Auth/OAuth subset of the canonical error contract owned by Identity.
 *
 * <p>Only the statuses reachable from the Identity Auth/OAuth HTTP boundary are reproduced;
 * generic Carelog domain errors stay outside this module. Status codes and messages are part of
 * the public wire contract.
 */
public enum IdentityErrorStatus {

    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 제공자입니다."),
    UNSUPPORTED_CLIENT_CHANNEL(HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 클라이언트 채널입니다."),
    INVALID_PRODUCT_CLIENT_ID(HttpStatus.BAD_REQUEST, "유효하지 않은 제품 인증 Client입니다."),
    INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING(HttpStatus.BAD_REQUEST, "제품 인증 Client와 채널 조합이 유효하지 않습니다."),
    INVALID_OAUTH_RETURN_TO(HttpStatus.BAD_REQUEST, "유효하지 않은 로그인 완료 후 이동 경로입니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 Refresh Token입니다."),
    INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 인증 요청입니다."),
    UNKNOWN_PRODUCT_CLIENT(HttpStatus.UNAUTHORIZED, "알 수 없는 제품 인증 Client입니다."),
    OAUTH_PROVIDER_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "OAuth 인증에 실패했습니다."),

    DISABLED_PRODUCT_CLIENT(HttpStatus.FORBIDDEN, "비활성화된 제품 인증 Client입니다."),

    REFRESH_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Refresh Token을 찾을 수 없습니다."),

    OAUTH_ACCOUNT_NOT_LINKED(HttpStatus.CONFLICT, "연결된 Carelog 계정이 없습니다."),
    OAUTH_IDENTITY_CONFLICT(HttpStatus.CONFLICT, "OAuth 계정 연결 상태가 유효하지 않습니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 예상치 못한 오류가 발생했습니다."),

    OAUTH_STATE_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "OAuth 인증 요청 저장소를 사용할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    IdentityErrorStatus(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
