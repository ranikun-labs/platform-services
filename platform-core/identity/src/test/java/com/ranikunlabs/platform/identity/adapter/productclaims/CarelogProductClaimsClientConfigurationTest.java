package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException.Reason;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CarelogProductClaimsClientConfigurationTest {

    private static final String ENABLED = "platform.identity.product-claims.enabled=true";
    private static final String BASE_URL =
            "platform.identity.product-claims.base-url=http://carelog.example.test:8080";
    private static final String SERVICE_SECRET =
            "platform.identity.product-claims.service-secret=platform-identity-service-secret-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CarelogProductClaimsClientConfiguration.class);

    @Test
    void disabledConfigurationDoesNotCreateHttpAdapterOrRestClient() {
        runner.withPropertyValues("platform.identity.product-claims.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CarelogProductClaimsProperties.class)
                        .doesNotHaveBean(CarelogProductClaimsHttpAdapter.class)
                        .doesNotHaveBean(CRMIdentityProjectionPort.class)
                        .doesNotHaveBean(RestClient.class));
    }

    @Test
    void enabledConfigurationCreatesOneCarelogClaimsPortWithBoundedDefaults() {
        runner.withPropertyValues(ENABLED, BASE_URL, SERVICE_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(CarelogProductClaimsProperties.class)
                            .hasSingleBean(RestClient.class)
                            .hasSingleBean(CRMIdentityProjectionPort.class);
                    assertThat(context.getBean(CRMIdentityProjectionPort.class))
                            .isInstanceOf(CarelogProductClaimsHttpAdapter.class);

                    CarelogProductClaimsProperties properties =
                            context.getBean(CarelogProductClaimsProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.requiredBaseUri().toString())
                            .isEqualTo("http://carelog.example.test:8080");
                });
    }

    @Test
    void enabledConfigurationRequiresServiceSecret() {
        runner.withPropertyValues(ENABLED, BASE_URL)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRequiresBaseUrl() {
        runner.withPropertyValues(ENABLED, SERVICE_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsInvalidBaseUrl() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=not-a-url",
                        SERVICE_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsNonHttpBaseUrl() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=ftp://carelog.example.test",
                        SERVICE_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsWeakServiceSecretWithoutDisclosingIt() {
        String weakSecret = "short-service-secret-regression";

        runner.withPropertyValues(
                        ENABLED,
                        BASE_URL,
                        "platform.identity.product-claims.service-secret=" + weakSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure().toString()).doesNotContain(weakSecret);
                });
    }

    @Test
    void enabledConfigurationRejectsBasePathThatCouldDuplicateFixedApiPath() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=http://carelog.example.test/api/v1",
                        SERVICE_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://carelog.example.test/",
            "http://carelog.example.test?query=secret",
            "http://carelog.example.test#fragment",
            "http://user:password@carelog.example.test"
    })
    void enabledConfigurationRejectsAnyNonOriginBaseUrl(String baseUrl) {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=" + baseUrl,
                        SERVICE_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredRestClientNeverFollowsRedirects() throws Exception {
        MockWebServer webServer = new MockWebServer();
        webServer.start();
        try {
            String origin = webServer.url("/").toString();
            origin = origin.substring(0, origin.length() - 1);
            webServer.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", origin + "/redirect-target"));

            runner.withPropertyValues(
                            ENABLED,
                            "platform.identity.product-claims.base-url=" + origin,
                            SERVICE_SECRET)
                    .run(context -> {
                        CRMIdentityProjectionException exception =
                                org.assertj.core.api.Assertions.catchThrowableOfType(
                                        () -> context.getBean(CRMIdentityProjectionPort.class)
                                                .findIdentityClaims(UUID.randomUUID()),
                                        CRMIdentityProjectionException.class);
                        assertThat(exception.reason()).isEqualTo(Reason.DEPENDENCY_CONTRACT_FAILURE);
                    });

            assertThat(webServer.getRequestCount()).isEqualTo(1);
        } finally {
            webServer.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "platform.identity.product-claims.connect-timeout=0s",
            "platform.identity.product-claims.connect-timeout=-1s",
            "platform.identity.product-claims.read-timeout=0s",
            "platform.identity.product-claims.read-timeout=-1s"
    })
    void enabledConfigurationRejectsNonPositiveTimeouts(String timeoutProperty) {
        runner.withPropertyValues(ENABLED, BASE_URL, SERVICE_SECRET, timeoutProperty)
                .run(context -> assertThat(context).hasFailed());
    }
}
