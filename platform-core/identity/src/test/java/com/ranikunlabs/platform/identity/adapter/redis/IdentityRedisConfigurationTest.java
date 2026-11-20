package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
            assertThat(context.getBean(LettuceConnectionFactory.class).isClusterAware()).isFalse();
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

    @Test
    void clusterNodesSelectClusterWithTls() {
        runner.withPropertyValues(
                "platform.identity.redis-cluster-nodes=cluster-a:6379,cluster-b:6380",
                "platform.identity.redis-tls-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.isClusterAware()).isTrue();
            assertThat(factory.isUseSsl()).isTrue();
            assertThat(factory.getClusterConfiguration().getClusterNodes()).hasSize(2);
        });
    }

    @Test
    void blankClusterNodesFailStartup() {
        runner.withPropertyValues("PLATFORM_IDENTITY_REDIS_CLUSTER_NODES=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void environmentClusterNodesSelectCluster() {
        runner.withPropertyValues("PLATFORM_IDENTITY_REDIS_CLUSTER_NODES=cluster-a:6379")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LettuceConnectionFactory.class).isClusterAware()).isTrue();
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_CLUSTER_TLS_TEST_NODES", matches = ".+")
    void tlsClusterExecutesGetdelAndBlacklistWithTtl() {
        runner.withPropertyValues(
                "platform.identity.redis-cluster-nodes=" + System.getenv("REDIS_CLUSTER_TLS_TEST_NODES"),
                "platform.identity.redis-tls-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var template = new StringRedisTemplate(context.getBean(LettuceConnectionFactory.class));
            template.afterPropertiesSet();
            var store = new RedisOAuthStateStore(template, new ObjectMapper());
            String id = UUID.randomUUID().toString();
            var record = new OAuthStateRecord("test", URI.create("https://example.test"), "/", "v", "n", Instant.now());
            store.save(id, record, Duration.ofSeconds(60));
            assertThat(template.getExpire("oauth:state:" + id)).isBetween(1L, 60L);
            assertThat(store.consume(id)).contains(record);
            assertThat(store.consume(id)).isEmpty();
            new RedisTokenBlacklistAdapter(template).addToBlacklist(id, Duration.ofSeconds(60));
            assertThat(template.opsForValue().get("blacklist:" + id)).isEqualTo("1");
            assertThat(template.getExpire("blacklist:" + id)).isBetween(1L, 60L);
            template.delete("blacklist:" + id);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityRuntimeProperties.class)
    @Import(IdentityRedisConfiguration.class)
    static class TestConfig { }
}
