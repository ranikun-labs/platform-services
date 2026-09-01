package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CarelogProductClaimsHttpAdapterTest {

    private static final String BASE_URL = "http://carelog.example.test";
    private static final String SERVICE_TOKEN = "carelog-service-token-test-only";
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PUBLIC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String REQUEST_URL = BASE_URL
            + "/api/v1/internal/identity/accounts/" + ACCOUNT_ID + "/claims";

    private MockRestServiceServer server;
    private CarelogProductClaimsHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CarelogProductClaimsHttpAdapter(builder.build(), SERVICE_TOKEN);
    }

    @Test
    void getClaimsUsesExactPathAndDedicatedHeaderWithoutBearer() {
        expectJson("{\"organizationId\":\"" + ORGANIZATION_ID
                + "\",\"role\":\"MANAGER\",\"publicId\":\"" + PUBLIC_ID + "\"}");

        assertThat(adapter.getIdentityClaims(ACCOUNT_ID))
                .isEqualTo(new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID));

        server.verify();
    }

    @Test
    void findClaimsMapsCarelogNotFoundToEmpty() {
        expectStatus(HttpStatus.NOT_FOUND);

        assertThat(adapter.findIdentityClaims(ACCOUNT_ID)).isEmpty();

        server.verify();
    }

    @Test
    void getClaimsMapsCarelogNotFoundToTypedProductProfileFailure() {
        expectStatus(HttpStatus.NOT_FOUND);

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.getIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.PRODUCT_PROFILE_NOT_FOUND);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailuresDoNotBecomeProductProfileNotFound(int status) {
        expectStatus(HttpStatus.valueOf(status));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_AUTH_FAILURE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void unavailableStatusesFailClosed(int status) {
        expectStatus(HttpStatus.valueOf(status));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_UNAVAILABLE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 422})
    void unexpectedClientStatusesAreContractFailures(int status) {
        expectStatus(HttpStatus.valueOf(status));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_CONTRACT_FAILURE);
        server.verify();
    }

    @Test
    void malformedJsonIsAContractFailureWithoutResponseLeakage() {
        expectJson("{");

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_CONTRACT_FAILURE);
        assertThat(exception.toString())
                .doesNotContain(SERVICE_TOKEN, "sensitive-carelog-body");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("invalidClaimsResponses")
    void invalidClaimsAreRejectedWithoutNormalization(String responseBody) {
        expectJson(responseBody);

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_CONTRACT_FAILURE);
        server.verify();
    }

    @Test
    void connectionFailureIsDependencyUnavailableAndNotRetried() {
        expectTransportFailure(new ResourceAccessException(
                "connection refused", new ConnectException("sensitive host")));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_UNAVAILABLE);
        server.verify();
    }

    @Test
    void readFailureIsDependencyUnavailableAndNotRetried() {
        expectTransportFailure(new ResourceAccessException(
                "read timeout", new SocketTimeoutException("sensitive timeout")));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.findIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_UNAVAILABLE);
        server.verify();
    }

    @Test
    void dependencyFailureDoesNotContainServiceTokenOrRawCarelogBody() {
        server.expect(times(1), requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(CarelogProductClaimsHttpAdapter.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("raw-carelog-secret-body"));

        CRMIdentityProjectionException exception = catchThrowableOfType(
                () -> adapter.getIdentityClaims(ACCOUNT_ID), CRMIdentityProjectionException.class);

        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_UNAVAILABLE);
        assertThat(exception.getMessage()).isNull();
        assertThat(exception.toString())
                .doesNotContain(SERVICE_TOKEN, "raw-carelog-secret-body");
        server.verify();
    }

    private void expectJson(String body) {
        server.expect(times(1), requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(CarelogProductClaimsHttpAdapter.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectStatus(HttpStatus status) {
        server.expect(times(1), requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(CarelogProductClaimsHttpAdapter.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(status).body("sensitive-carelog-body"));
    }

    private void expectTransportFailure(ResourceAccessException failure) {
        server.expect(times(1), requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(CarelogProductClaimsHttpAdapter.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andRespond(request -> {
                    throw failure;
                });
    }

    private static Stream<String> invalidClaimsResponses() {
        return Stream.of(
                "{\"organizationId\":null,\"role\":\"MANAGER\",\"publicId\":\""
                        + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\"MANAGER\",\"publicId\":null}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":null,\"publicId\":\"" + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\"\",\"publicId\":\"" + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\" \",\"publicId\":\"" + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\"ROLE_MANAGER\",\"publicId\":\"" + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\"ROLE_ROLE_MANAGER\",\"publicId\":\"" + PUBLIC_ID + "\"}",
                "{\"organizationId\":\"" + ORGANIZATION_ID
                        + "\",\"role\":\"ADMIN\",\"publicId\":\"" + PUBLIC_ID + "\"}");
    }
}
