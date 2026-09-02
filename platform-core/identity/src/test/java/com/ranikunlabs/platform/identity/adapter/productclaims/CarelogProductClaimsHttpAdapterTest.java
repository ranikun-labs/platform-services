package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.ProductClaimsException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the Product claims failure semantics that a hosted end-to-end path cannot reach:
 * the absent-versus-unavailable distinction between the two port operations, and a dependency that
 * cannot be contacted at all.
 */
class CarelogProductClaimsHttpAdapterTest {

    private static final String SERVICE_TOKEN = "adapter-test-service-token";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private HttpServer server;
    private final AtomicReference<StubResponse> response = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        response.set(new StubResponse(200, "{}"));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            StubResponse configured = response.get();
            byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(configured.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void anAbsentSnapshotIsEmptyForFindAndNotFoundForGet() {
        response.set(new StubResponse(404, ""));
        CarelogProductClaimsHttpAdapter adapter = adapterFor(server.getAddress().getPort());

        assertThat(adapter.findIdentityClaims(ACCOUNT_ID)).isEmpty();
        assertThatThrownBy(() -> adapter.getIdentityClaims(ACCOUNT_ID))
                .isInstanceOfSatisfying(ProductClaimsException.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(ProductClaimsException.Reason.PRODUCT_CLAIMS_NOT_FOUND));
    }

    @Test
    void anUnreachableDependencyIsNeverReportedAsAnAbsentSnapshot() throws IOException {
        CarelogProductClaimsHttpAdapter adapter = adapterFor(closedPort());

        // Both operations must fail loudly; an empty Optional here would read as "no Product claims".
        assertThatThrownBy(() -> adapter.findIdentityClaims(ACCOUNT_ID))
                .isInstanceOfSatisfying(ProductClaimsException.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(ProductClaimsException.Reason.PRODUCT_CLAIMS_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.getIdentityClaims(ACCOUNT_ID))
                .isInstanceOf(ProductClaimsException.class);
    }

    @Test
    void aRejectedServiceCredentialIsAConfigurationFaultNotAnAbsentSnapshot() {
        response.set(new StubResponse(403, ""));
        CarelogProductClaimsHttpAdapter adapter = adapterFor(server.getAddress().getPort());

        assertThatThrownBy(() -> adapter.findIdentityClaims(ACCOUNT_ID))
                .isInstanceOfSatisfying(ProductClaimsException.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(ProductClaimsException.Reason.PRODUCT_CLAIMS_ACCESS_DENIED));
    }

    @Test
    void aCompleteSnapshotIsMappedOntoTheProductNeutralClaims() {
        UUID organizationId = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        response.set(new StubResponse(200, "{\"organizationId\":\"" + organizationId
                + "\",\"role\":\"MANAGER\",\"publicId\":\"" + publicId + "\"}"));

        CRMIdentityClaims claims = adapterFor(server.getAddress().getPort()).getIdentityClaims(ACCOUNT_ID);

        assertThat(claims).isEqualTo(new CRMIdentityClaims(organizationId, "MANAGER", publicId));
    }

    private CarelogProductClaimsHttpAdapter adapterFor(int port) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
        return new CarelogProductClaimsHttpAdapter(restClient, SERVICE_TOKEN);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record StubResponse(int status, String body) {
    }
}
