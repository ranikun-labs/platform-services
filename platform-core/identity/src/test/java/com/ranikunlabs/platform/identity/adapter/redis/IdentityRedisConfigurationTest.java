package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRedisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "platform.identity.datasource.url=jdbc:postgresql://localhost/test",
                    "platform.identity.datasource.username=test",
                    "platform.identity.datasource.password=test",
                    "platform.identity.redis-host=localhost",
                    "platform.identity.redis-port=6379",
                    "platform.identity.redis-key-prefix=test:");

    @Test
    void tlsDefaultsOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LettuceConnectionFactory.class).isUseSsl()).isFalse();
        });
    }

    @Test
    void tlsCanBeEnabled() {
        runner.withPropertyValues("platform.identity.redis-tls-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdentityRuntimeProperties.class).redisTlsEnabled()).isTrue();
            assertThat(context.getBean(LettuceConnectionFactory.class).isUseSsl()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityRuntimeProperties.class)
    @Import(IdentityRedisConfiguration.class)
    static class TestConfig { }
}
