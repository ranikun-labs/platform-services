package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.ProductClaimsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the Product-owned claim snapshot over Carelog's private service-to-service API.
 *
 * <p>Identity consumes only the published wire contract: it never touches a Carelog repository,
 * entity, or database. The call is authenticated with a dedicated service token — not a Gateway
 * secret, a user Bearer JWT, or {@code X-User-*} headers — and only the three claim fields are
 * deserialized, so Carelog's user domain never crosses the boundary.
 */
public class CarelogProductClaimsHttpAdapter implements CRMIdentityProjectionPort {

    static final String CLAIMS_PATH = "/api/v1/internal/identity/accounts/{accountId}/claims";
    static final String SERVICE_TOKEN_HEADER = "X-Platform-Service-Token";

    private static final Logger log = LoggerFactory.getLogger(CarelogProductClaimsHttpAdapter.class);

    private final RestClient restClient;
    private final String serviceToken;

    public CarelogProductClaimsHttpAdapter(RestClient restClient, String serviceToken) {
        this.restClient = restClient;
        this.serviceToken = serviceToken;
    }

    @Override
    public CRMIdentityClaims getIdentityClaims(UUID accountId) {
        return findIdentityClaims(accountId)
                .orElseThrow(() -> new ProductClaimsException(
                        ProductClaimsException.Reason.PRODUCT_CLAIMS_NOT_FOUND));
    }

    @Override
    public Optional<CRMIdentityClaims> findIdentityClaims(UUID accountId) {
        try {
            return restClient.get()
                    .uri(CLAIMS_PATH, accountId)
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .exchange((request, response) -> readClaims(response.getStatusCode(), response));
        } catch (ProductClaimsException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            // Connection refused, DNS failure, connect or read timeout.
            log.error("Product claims dependency unreachable");
            throw unavailable();
        } catch (RestClientException exception) {
            // Body present but undecodable against the claims contract.
            log.error("Product claims response could not be decoded");
            throw invalidResponse();
        }
    }

    private Optional<CRMIdentityClaims> readClaims(
            HttpStatusCode status,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        if (status.value() == 404) {
            // The account genuinely has no Product claim snapshot.
            return Optional.empty();
        }
        if (status.value() == 401 || status.value() == 403) {
            log.error("Product claims service credential rejected by the Product");
            throw new ProductClaimsException(
                    ProductClaimsException.Reason.PRODUCT_CLAIMS_ACCESS_DENIED);
        }
        if (status.isError()) {
            log.error("Product claims request failed with status {}", status.value());
            throw unavailable();
        }
        return Optional.of(toClaims(response.bodyTo(ProductClaimsResponse.class)));
    }

    private CRMIdentityClaims toClaims(ProductClaimsResponse body) {
        if (body == null
                || body.organizationId() == null
                || body.publicId() == null
                || body.role() == null
                || body.role().isBlank()) {
            log.error("Product claims response was missing a required claim");
            throw invalidResponse();
        }
        return new CRMIdentityClaims(body.organizationId(), body.role(), body.publicId());
    }

    private ProductClaimsException unavailable() {
        return new ProductClaimsException(ProductClaimsException.Reason.PRODUCT_CLAIMS_UNAVAILABLE);
    }

    private ProductClaimsException invalidResponse() {
        return new ProductClaimsException(
                ProductClaimsException.Reason.PRODUCT_CLAIMS_INVALID_RESPONSE);
    }
}
