package com.ranikunlabs.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsOnlyTheGatewayNamespace() {
        contextRunner
                .withPropertyValues(
                        "platform.gateway.redis-host=redis",
                        "platform.gateway.redis-port=6379",
                        "platform.gateway.redis-key-prefix=platform:gateway:")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayRuntimeProperties properties = context.getBean(GatewayRuntimeProperties.class);
                    assertThat(properties.redisHost()).isEqualTo("redis");
                    assertThat(properties.redisKeyPrefix()).isEqualTo("platform:gateway:");
                });
    }

    @Test
    void failsWhenARequiredGatewayValueIsMissing() {
        contextRunner
                .withPropertyValues(
                        "platform.gateway.redis-host=redis",
                        "platform.gateway.redis-port=6379")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotUseIdentityNamespaceForGatewayConfiguration() {
        contextRunner
                .withPropertyValues(
                        "platform.identity.redis-host=redis",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=platform:identity:")
                .run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayRuntimeProperties.class)
    static class TestConfig {
    }
}
