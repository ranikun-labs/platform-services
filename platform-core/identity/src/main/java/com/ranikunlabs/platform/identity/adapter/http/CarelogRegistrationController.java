package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public exposure is separately gated by the DEV-only configuration and Gateway. */
@RestController
@ConditionalOnProperty(name = "platform.identity.carelog-registration.enabled", havingValue = "true")
final class CarelogRegistrationController {
    static final String PATH = "/api/v1/auth/carelog/registration";
    private final CarelogRegistrationCoordinator coordinator;

    CarelogRegistrationController(CarelogRegistrationCoordinator coordinator) { this.coordinator = coordinator; }

    @PostMapping(PATH)
    ResponseEntity<ApiResponse<Object>> register(@RequestHeader HttpHeaders headers, @Valid @RequestBody Request request) {
        List<String> keys = headers.get("Idempotency-Key");
        if (keys == null || keys.size() != 1 || keys.getFirst().contains(",")) return response(HttpStatus.BAD_REQUEST, "Invalid registration request.");
        try {
            return switch (coordinator.register(keys.getFirst(), request.userId(), request.email(), request.password(), request.name())) {
                case COMPLETED -> response(HttpStatus.CREATED, "Registration completed.");
                case PENDING -> response(HttpStatus.SERVICE_UNAVAILABLE, "Registration pending. Retry with the same key.");
                case CONFLICT -> response(HttpStatus.CONFLICT, "Registration conflict.");
            };
        } catch (InvalidRequestException invalid) {
            return response(HttpStatus.BAD_REQUEST, "Invalid registration request.");
        } catch (RuntimeException unavailable) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "Registration unavailable.");
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Object>> invalid(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "Invalid registration request.");
    }

    private static ResponseEntity<ApiResponse<Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(ApiResponse.of(status, message));
    }

    record Request(@NotBlank @Size(max = 255) String userId,
                   @NotBlank @Email @Size(max = 255) String email,
                   @NotNull @Size(max = 40) String password,
                   @NotBlank @Size(max = 255) String name) {
        Request {
            userId = trimEcmaWhitespace(userId);
            email = trimEcmaWhitespace(email);
            name = trimEcmaWhitespace(name);
        }
        @Override public String toString() { return "CarelogRegistrationRequest[redacted]"; }
    }

    /** Match the coordinator's case-preserving ECMAScript trim before Bean Validation. */
    private static String trimEcmaWhitespace(String value) {
        if (value == null) return null;
        // Controls are rejected by the coordinator and must not be hidden by trimming.
        if (value.codePoints().anyMatch(Character::isISOControl)) return value;
        int start = 0;
        int end = value.length();
        while (start < end && isEcmaWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isEcmaWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean isEcmaWhitespace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0d) || codePoint == 0x20
                || codePoint == 0xa0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000 || codePoint == 0xfeff;
    }
}
