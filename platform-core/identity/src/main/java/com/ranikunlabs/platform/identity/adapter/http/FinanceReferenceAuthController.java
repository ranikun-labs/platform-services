package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.FinanceReferenceAccessService;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.FinanceReferenceLoginRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.FinanceReferenceAccessTokenResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping(FinanceReferenceAuthController.PATH)
public final class FinanceReferenceAuthController {

    public static final String PATH = "/api/v1/auth/finance-reference/login";
    private static final int MAX_BODY_BYTES = 2048;
    private static final int MAX_USER_ID_BYTES = 128;
    private static final int MAX_PASSWORD_BYTES = 72;

    private final FinanceReferenceAccessService service;

    public FinanceReferenceAuthController(FinanceReferenceAccessService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FinanceReferenceAccessTokenResponse>> login(
            @Valid @RequestBody FinanceReferenceLoginRequest request
    ) {
        if (request == null || request.userId() == null || request.password() == null
                || utf8Bytes(request.userId()) == 0 || utf8Bytes(request.password()) == 0
                || utf8Bytes(request.userId()) > MAX_USER_ID_BYTES
                || utf8Bytes(request.password()) > MAX_PASSWORD_BYTES
                || utf8Bytes(request.userId()) + utf8Bytes(request.password()) > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "Invalid Finance reference request.");
        }
        try {
            IssuedFinanceReferenceAccessToken issued = service.authenticate(request.userId(), request.password());
            FinanceReferenceAccessTokenResponse body = new FinanceReferenceAccessTokenResponse(
                    issued.value(), "Bearer", DateTimeFormatter.ISO_INSTANT.format(issued.expiresAt()));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(ApiResponse.ok(body).getBody());
        } catch (InvalidCredentialsException exception) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid credentials.");
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "identity_verification_unavailable");
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<FinanceReferenceAccessTokenResponse>> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "Invalid Finance reference request.");
    }

    private ResponseEntity<ApiResponse<FinanceReferenceAccessTokenResponse>> error(
            HttpStatus status,
            String message
    ) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.<FinanceReferenceAccessTokenResponse>of(status, message));
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
