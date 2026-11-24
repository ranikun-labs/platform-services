package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.request.FinanceReferenceSignupRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.FinanceReferenceSignupResponse;
import com.ranikunlabs.platform.identity.application.FinanceReferenceSignupService;
import com.ranikunlabs.platform.identity.application.FinanceReferenceSignupService.InvalidFinanceSignupException;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;

import java.util.Locale;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public DEV signup for Finance reference accounts. Mounted only when signup is explicitly enabled. */
@RestController
public final class FinanceReferenceSignupController {

    static final String PATH = "/api/v1/auth/finance-reference/signup";

    private final FinanceReferenceSignupService service;

    public FinanceReferenceSignupController(FinanceReferenceSignupService service) {
        this.service = service;
    }

    @PostMapping(PATH)
    ResponseEntity<ApiResponse<FinanceReferenceSignupResponse>> signup(
            @Valid @RequestBody FinanceReferenceSignupRequest request
    ) {
        try {
            UUID accountId = service.register(request.email(), request.password());
            return withNoStore(ApiResponse.created(new FinanceReferenceSignupResponse(
                    accountId.toString(), request.email().trim().toLowerCase(Locale.ROOT))));
        } catch (InvalidFinanceSignupException exception) {
            return error(HttpStatus.BAD_REQUEST, "Invalid Finance signup request.");
        } catch (DuplicateLoginIdException exception) {
            return error(HttpStatus.CONFLICT, "Account already exists.");
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "identity_registration_unavailable");
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<FinanceReferenceSignupResponse>> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "Invalid Finance signup request.");
    }

    private static ResponseEntity<ApiResponse<FinanceReferenceSignupResponse>> error(HttpStatus status, String message) {
        return withNoStore(ApiResponse.error(status, message));
    }

    private static <T> ResponseEntity<T> withNoStore(ResponseEntity<T> response) {
        return ResponseEntity.status(response.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response.getBody());
    }
}
