package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Narrow HTTP failure mapping for the Identity Auth/OAuth boundary.
 *
 * <p>Scoped to {@link IdentityAuthController} so hosting Identity never changes how the rest of
 * the Platform Core host reports errors. Response bodies carry only the canonical status and
 * message: no stack traces, SQL, Redis, provider payloads, secrets, or tokens.
 */
@RestControllerAdvice(assignableTypes = IdentityAuthController.class)
public class IdentityAuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityAuthExceptionHandler.class);

    @ExceptionHandler(IdentityHttpException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleIdentityHttpException(IdentityHttpException e) {
        return respond(e.errorStatus());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleInvalidCredentials(InvalidCredentialsException e) {
        return respond(IdentityErrorStatus.INVALID_CREDENTIALS);
    }

    @ExceptionHandler(TokenLifecycleException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleTokenLifecycle(TokenLifecycleException e) {
        return respond(switch (e.reason()) {
            case INVALID_REFRESH_TOKEN -> IdentityErrorStatus.INVALID_REFRESH_TOKEN;
            case REFRESH_TOKEN_NOT_FOUND -> IdentityErrorStatus.REFRESH_TOKEN_NOT_FOUND;
            case REFRESH_TOKEN_EXPIRED -> IdentityErrorStatus.REFRESH_TOKEN_EXPIRED;
        });
    }

    @ExceptionHandler(OAuthAuthorizationRequestException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleOAuthAuthorizationRequest(
            OAuthAuthorizationRequestException e
    ) {
        return respond(switch (e.reason()) {
            case UNSUPPORTED_OAUTH_PROVIDER -> IdentityErrorStatus.UNSUPPORTED_OAUTH_PROVIDER;
            case INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING ->
                    IdentityErrorStatus.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING;
            case UNSUPPORTED_CLIENT_CHANNEL -> IdentityErrorStatus.UNSUPPORTED_CLIENT_CHANNEL;
            case INVALID_OAUTH_RETURN_TO -> IdentityErrorStatus.INVALID_OAUTH_RETURN_TO;
        });
    }

    @ExceptionHandler(ProductClientLookupException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleProductClientLookup(ProductClientLookupException e) {
        return respond(switch (e.reason()) {
            case INVALID_PRODUCT_CLIENT_ID -> IdentityErrorStatus.INVALID_PRODUCT_CLIENT_ID;
            case UNKNOWN_PRODUCT_CLIENT -> IdentityErrorStatus.UNKNOWN_PRODUCT_CLIENT;
            case DISABLED_PRODUCT_CLIENT -> IdentityErrorStatus.DISABLED_PRODUCT_CLIENT;
        });
    }

    /** A state-store outage is a temporary infrastructure failure, not an authentication failure. */
    @ExceptionHandler(OAuthStateStoreUnavailableException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleOAuthStateStoreUnavailable(
            OAuthStateStoreUnavailableException e
    ) {
        log.warn("OAuth state store unavailable");
        return respond(IdentityErrorStatus.OAUTH_STATE_STORE_UNAVAILABLE);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(IdentityErrorStatus.INVALID_REQUEST.message());
        return ResponseEntity
                .status(IdentityErrorStatus.INVALID_REQUEST.httpStatus())
                .body(IdentityApiResponse.of(IdentityErrorStatus.INVALID_REQUEST.httpStatus(), message));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<IdentityApiResponse<Object>> handleMalformedRequest(Exception e) {
        return respond(IdentityErrorStatus.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IdentityApiResponse<Object>> handleUnexpected(Exception e) {
        log.error("Unhandled Identity Auth HTTP failure", e);
        return respond(IdentityErrorStatus.INTERNAL_ERROR);
    }

    private ResponseEntity<IdentityApiResponse<Object>> respond(IdentityErrorStatus errorStatus) {
        return ResponseEntity
                .status(errorStatus.httpStatus())
                .body(IdentityApiResponse.of(errorStatus.httpStatus(), errorStatus.message()));
    }
}
