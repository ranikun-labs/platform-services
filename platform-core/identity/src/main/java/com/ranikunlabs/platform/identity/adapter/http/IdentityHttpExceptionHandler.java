package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Small, controller-scoped mapper for Identity-owned HTTP failures. */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class IdentityHttpExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidCredentials(InvalidCredentialsException ignored) {
        return error(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다.");
    }

    @ExceptionHandler(TokenLifecycleException.class)
    public ResponseEntity<ApiResponse<Object>> handleTokenLifecycle(TokenLifecycleException exception) {
        return switch (exception.reason()) {
            case INVALID_REFRESH_TOKEN -> error(
                    HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다.");
            case REFRESH_TOKEN_EXPIRED -> error(
                    HttpStatus.UNAUTHORIZED, "만료된 Refresh Token입니다.");
            case REFRESH_TOKEN_NOT_FOUND -> error(
                    HttpStatus.NOT_FOUND, "Refresh Token을 찾을 수 없습니다.");
        };
    }

    @ExceptionHandler(OAuthRequestException.class)
    public ResponseEntity<ApiResponse<Object>> handleOAuthRequest(OAuthRequestException exception) {
        return switch (exception.reason()) {
            case UNSUPPORTED_OAUTH_PROVIDER -> error(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 제공자입니다.");
            case UNSUPPORTED_CLIENT_CHANNEL -> error(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 클라이언트 채널입니다.");
            case INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING -> error(
                    HttpStatus.BAD_REQUEST, "제품 인증 Client와 채널 조합이 유효하지 않습니다.");
            case INVALID_OAUTH_RETURN_TO -> error(
                    HttpStatus.BAD_REQUEST, "유효하지 않은 로그인 완료 후 이동 경로입니다.");
        };
    }

    @ExceptionHandler(ProductClientLookupException.class)
    public ResponseEntity<ApiResponse<Object>> handleProductClientLookup(
            ProductClientLookupException exception
    ) {
        return switch (exception.reason()) {
            case INVALID_PRODUCT_CLIENT_ID -> error(
                    HttpStatus.BAD_REQUEST, "유효하지 않은 제품 인증 Client입니다.");
            case UNKNOWN_PRODUCT_CLIENT -> error(
                    HttpStatus.UNAUTHORIZED, "알 수 없는 제품 인증 Client입니다.");
            case DISABLED_PRODUCT_CLIENT -> error(
                    HttpStatus.FORBIDDEN, "비활성화된 제품 인증 Client입니다.");
        };
    }

    @ExceptionHandler(OAuthProviderException.class)
    public ResponseEntity<ApiResponse<Object>> handleOAuthProvider(OAuthProviderException ignored) {
        return error(HttpStatus.UNAUTHORIZED, "OAuth 인증에 실패했습니다.");
    }

    @ExceptionHandler(OAuthStateStoreUnavailableException.class)
    public ResponseEntity<ApiResponse<Object>> handleOAuthStateStoreUnavailable(
            OAuthStateStoreUnavailableException ignored
    ) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OAuth 인증 요청 저장소를 사용할 수 없습니다.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("요청 값이 유효하지 않습니다.");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Object>> handleMalformedRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ignored) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 예상치 못한 오류가 발생했습니다.");
    }

    private ResponseEntity<ApiResponse<Object>> error(HttpStatus status, String message) {
        return ApiResponse.error(status, message);
    }
}
