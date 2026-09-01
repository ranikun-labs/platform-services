package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException.Reason;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.UUID;

/** HTTP adapter for the Carelog-owned Product claims projection port. */
public final class CarelogProductClaimsHttpAdapter implements CRMIdentityProjectionPort {

    static final String CLAIMS_PATH = "/api/v1/internal/identity/accounts/{accountId}/claims";
    static final String SERVICE_TOKEN_HEADER = "X-Platform-Service-Token";

    private final RestClient restClient;
    private final String serviceToken;

    public CarelogProductClaimsHttpAdapter(RestClient restClient, String serviceToken) {
        this.restClient = restClient;
        this.serviceToken = serviceToken;
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
        try {
            CarelogProductClaimsResponse response = restClient.get()
                    .uri(CLAIMS_PATH, accountId)
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .retrieve()
                    .body(CarelogProductClaimsResponse.class);
            return Optional.of(validate(response));
        } catch (RestClientResponseException exception) {
            return mapHttpFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        } catch (IllegalArgumentException exception) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
    }

    private CRMIdentityClaims validate(CarelogProductClaimsResponse response) {
        if (response == null
                || response.organizationId() == null
                || response.publicId() == null
                || !"MANAGER".equals(response.role())) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
        return new CRMIdentityClaims(
                response.organizationId(),
                response.role(),
                response.publicId());
    }

    private Optional<CRMIdentityClaims> mapHttpFailure(int status) {
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_AUTH_FAILURE);
        }
        if (status == 429 || status >= 500) {
            throw new CRMIdentityProjectionException(Reason.DEPENDENCY_UNAVAILABLE);
        }
        throw new CRMIdentityProjectionException(Reason.DEPENDENCY_CONTRACT_FAILURE);
    }
}
