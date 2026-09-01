package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException.Reason;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.CRMIdentityClaimsPolicy;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** HTTP adapter for the Carelog-owned Product claims projection port. */
public final class CarelogProductClaimsHttpAdapter implements CRMIdentityProjectionPort {

    static final String CLAIMS_PATH = "/api/v1/internal/identity/claims/{accountId}";
    static final String SERVICE_ID_HEADER = "X-Service-Id";
    static final String SERVICE_SECRET_HEADER = "X-Service-Secret";
    static final String SERVICE_ID = "platform-identity";

    private final RestClient restClient;
    private final String serviceSecret;

    public CarelogProductClaimsHttpAdapter(RestClient restClient, String serviceSecret) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        if (serviceSecret == null || serviceSecret.isBlank() || serviceSecret.length() < 32) {
            throw new IllegalArgumentException("serviceSecret must be at least 32 characters");
        }
        this.serviceSecret = serviceSecret;
    }

    @Override
    public CRMIdentityClaims getIdentityClaims(UUID accountId) {
        return requestClaims(accountId)
                .orElseThrow(() -> new CRMIdentityProjectionException(Reason.PRODUCT_PROFILE_NOT_FOUND));
    }

    @Override
    public Optional<CRMIdentityClaims> findIdentityClaims(UUID accountId) {
        return requestClaims(accountId);
    }

    private Optional<CRMIdentityClaims> requestClaims(UUID accountId) {
        if (accountId == null) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
        try {
            return restClient.get()
                    .uri(CLAIMS_PATH, accountId)
                    .header(SERVICE_ID_HEADER, SERVICE_ID)
                    .header(SERVICE_SECRET_HEADER, serviceSecret)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.empty();
                        }
                        if (status != 200) {
                            throw mapHttpFailure(status);
                        }
                        return Optional.of(validate(response.bodyTo(CarelogProductClaimsResponse.class)));
                    });
        } catch (CRMIdentityProjectionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw mapHttpFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_UNAVAILABLE);
        } catch (RuntimeException exception) {
            if (isTransportFailure(exception)) {
                throw new CRMIdentityProjectionException(Reason.DEPENDENCY_UNAVAILABLE);
            }
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
    }

    private CRMIdentityClaims validate(CarelogProductClaimsResponse response) {
        if (response == null
                || response.organizationId() == null
                || response.publicId() == null
                || response.role() == null
                || response.role().isBlank()) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
        return CRMIdentityClaimsPolicy.requireEligible(new CRMIdentityClaims(
                response.organizationId(),
                response.role(),
                response.publicId()));
    }

    private CRMIdentityProjectionException mapHttpFailure(int status) {
        if (status == 401 || status == 403) {
            return new CRMIdentityProjectionException(Reason.DEPENDENCY_AUTH_FAILURE);
        }
        if (status == 429 || status >= 500) {
            return new CRMIdentityProjectionException(Reason.DEPENDENCY_UNAVAILABLE);
        }
        return new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
    }

    private boolean isTransportFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }
}
