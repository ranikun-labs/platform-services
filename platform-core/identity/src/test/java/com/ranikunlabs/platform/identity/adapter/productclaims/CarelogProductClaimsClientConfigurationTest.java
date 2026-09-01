package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CarelogProductClaimsClientConfigurationTest {

    private static final String ENABLED = "platform.identity.product-claims.enabled=true";
    private static final String BASE_URL =
            "platform.identity.product-claims.base-url=http://carelog.example.test:8080";
    private static final String SERVICE_TOKEN =
            "platform.identity.product-claims.service-token=test-service-token";

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
        runner.withPropertyValues(ENABLED, BASE_URL, SERVICE_TOKEN)
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
    void enabledConfigurationRequiresServiceToken() {
        runner.withPropertyValues(ENABLED, BASE_URL)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRequiresBaseUrl() {
        runner.withPropertyValues(ENABLED, SERVICE_TOKEN)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsInvalidBaseUrl() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=not-a-url",
                        SERVICE_TOKEN)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsNonHttpBaseUrl() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=ftp://carelog.example.test",
                        SERVICE_TOKEN)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRejectsBasePathThatCouldDuplicateFixedApiPath() {
        runner.withPropertyValues(
                        ENABLED,
                        "platform.identity.product-claims.base-url=http://carelog.example.test/api/v1",
                        SERVICE_TOKEN)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "platform.identity.product-claims.connect-timeout=0s",
            "platform.identity.product-claims.connect-timeout=-1s",
            "platform.identity.product-claims.read-timeout=0s",
            "platform.identity.product-claims.read-timeout=-1s"
    })
    void enabledConfigurationRejectsNonPositiveTimeouts(String timeoutProperty) {
        runner.withPropertyValues(ENABLED, BASE_URL, SERVICE_TOKEN, timeoutProperty)
                .run(context -> assertThat(context).hasFailed());
    }
}
