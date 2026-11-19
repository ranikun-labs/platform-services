package com.ranikunlabs.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsOnlyTheIdentityNamespace() {
        contextRunner
                .withPropertyValues(
                        "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "platform.identity.datasource.username=identity",
                        "platform.identity.datasource.password=provided-at-runtime",
                        "platform.identity.redis-host=redis",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=platform:identity:")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IdentityRuntimeProperties properties = context.getBean(IdentityRuntimeProperties.class);
                    assertThat(properties.datasource().url()).startsWith("jdbc:postgresql://postgres:5432");
                    assertThat(properties.datasource().username()).isEqualTo("identity");
                    assertThat(properties.redisKeyPrefix()).isEqualTo("platform:identity:");
                    assertThat(properties.redisTlsEnabled()).isFalse();
                });
    }

    @Test
    void failsWhenARequiredIdentityValueIsMissing() {
        contextRunner
                .withPropertyValues(
                        "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "platform.identity.datasource.username=identity",
                        "platform.identity.redis-host=redis",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=platform:identity:")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotAcceptGatewayNamespaceAsIdentityConfiguration() {
        contextRunner
                .withPropertyValues(
                        "platform.gateway.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "platform.gateway.redis-host=redis",
                        "platform.gateway.redis-port=6379",
                        "platform.gateway.redis-key-prefix=platform:gateway:")
                .run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityRuntimeProperties.class)
    static class TestConfig {
    }
}
