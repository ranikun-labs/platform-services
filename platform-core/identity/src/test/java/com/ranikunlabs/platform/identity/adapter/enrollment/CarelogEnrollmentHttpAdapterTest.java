package com.ranikunlabs.platform.identity.adapter.enrollment;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentConflictException;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentDetails;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CarelogEnrollmentHttpAdapterTest {
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ORIGIN = "http://carelog.example.test";
    private static final EnrollmentDetails DETAILS = new EnrollmentDetails("user", "test@example.test", "Name");
    private MockRestServiceServer server;
    private CarelogEnrollmentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ORIGIN);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CarelogEnrollmentHttpAdapter(builder.build(), "enrollment-token-01234567890123456789");
    }

    private void expectRequest() {
        server.expect(requestTo(ORIGIN + "/api/v1/internal/v1/identity/carelog/enrollments/" + ACCOUNT))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer enrollment-token-01234567890123456789"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"userId\":\"user\",\"email\":\"test@example.test\",\"name\":\"Name\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED));
    }

    @Test
    void sendsExactFlatProductFieldsAndAcceptsEmptyCreated() throws Exception {
        expectRequest();
        adapter.ensureEnrolled(ACCOUNT, DETAILS);
        server.verify();
        assertThat(DETAILS.toString()).doesNotContain("user", "example", "Name");
    }

    @Test
    void authenticatedConflictIsTerminal() {
        server.expect(requestTo(ORIGIN + "/api/v1/internal/v1/identity/carelog/enrollments/" + ACCOUNT))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> adapter.ensureEnrolled(ACCOUNT, DETAILS)).isInstanceOf(EnrollmentConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 301, 400, 401, 403, 404, 429, 500, 503})
    void otherStatusesRemainRetryable(int code) {
        server.expect(requestTo(ORIGIN + "/api/v1/internal/v1/identity/carelog/enrollments/" + ACCOUNT))
                .andRespond(withRawStatus(code));
        assertThatThrownBy(() -> adapter.ensureEnrolled(ACCOUNT, DETAILS)).isInstanceOf(EnrollmentUnavailableException.class);
    }

    @Test
    void nonEmpty201AndTransportErrorsRemainRetryable() {
        server.expect(requestTo(ORIGIN + "/api/v1/internal/v1/identity/carelog/enrollments/" + ACCOUNT))
                .andRespond(withStatus(HttpStatus.CREATED).body("unexpected"));
        assertThatThrownBy(() -> adapter.ensureEnrolled(ACCOUNT, DETAILS)).isInstanceOf(EnrollmentUnavailableException.class);
        server.reset();
        server.expect(requestTo(ORIGIN + "/api/v1/internal/v1/identity/carelog/enrollments/" + ACCOUNT))
                .andRespond(request -> { throw new ResourceAccessException("timeout"); });
        assertThatThrownBy(() -> adapter.ensureEnrolled(ACCOUNT, DETAILS)).isInstanceOf(EnrollmentUnavailableException.class);
    }

    @Test
    void configurationIsDisabledByDefaultAndEnabledOnlyWithValidProperties() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CarelogEnrollmentClientConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(CarelogEnrollmentHttpAdapter.class));
        runner.withPropertyValues("platform.identity.carelog-enrollment.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("platform.identity.carelog-enrollment.enabled=true",
                "platform.identity.carelog-enrollment.base-url=https://carelog.example.test",
                "platform.identity.carelog-enrollment.credential=enrollment-token-01234567890123456789")
                .run(context -> assertThat(context).hasSingleBean(CarelogEnrollmentHttpAdapter.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://carelog.example.test", "http://localhost", "http://127.0.0.2"})
    void rejectsNonLoopbackCleartextOrigins(String origin) {
        var properties = new CarelogEnrollmentProperties();
        properties.setBaseUrl(origin);
        assertThatThrownBy(properties::requiredBaseUri).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permitsLiteralLoopbackForLocalTestsAndHttpsForServices() {
        var properties = new CarelogEnrollmentProperties();
        for (String origin : new String[]{"http://127.0.0.1:8080", "http://[::1]:8080", "https://carelog.example.test"}) {
            properties.setBaseUrl(origin);
            assertThat(properties.requiredBaseUri().toString()).isEqualTo(origin);
        }
    }
}
