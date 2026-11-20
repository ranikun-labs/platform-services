package com.ranikunlabs.platform.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService;
import com.ranikunlabs.platform.gateway.ratelimit.OAuthFailClosedRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GatewayApplication.class, properties = {
        "server.port=0",
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=redis",
        "PLATFORM_GATEWAY_REDIS_PORT=6379",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_REDIS_CLUSTER_NODES=${REDIS_CLUSTER_TLS_TEST_NODES:cluster-a:6379,cluster-b:6379}",
        "PLATFORM_GATEWAY_REDIS_TLS_ENABLED=true",
        "PLATFORM_GATEWAY_AUTH_URI=http://localhost:8082",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=runtime-test-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-runtime-test-secret-key-long-enough"
})
class GatewayRedisClusterBootTest {
    @Autowired LettuceConnectionFactory connectionFactory;
    @Autowired ReactiveStringRedisTemplate redisTemplate;
    @Autowired RedisBlacklistService blacklist;
    @Autowired RedisRateLimiter delegate;
    @Autowired OAuthFailClosedRateLimiter limiter;

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_CLUSTER_TLS_TEST_NODES", matches = ".+")
    void tlsClusterExecutesBlacklistAndRateLimiter() {
        String id = UUID.randomUUID().toString();
        String key = "blacklist:" + id;
        assertThat(redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(30)).block()).isTrue();
        assertThat(blacklist.isBlacklisted(id).block()).isTrue();
        String route = "cluster-tls-test";
        delegate.getConfig().put(route, new RedisRateLimiter.Config()
                .setReplenishRate(1).setBurstCapacity(100).setRequestedTokens(100));
        assertThat(limiter.isAllowed(route, id).block(Duration.ofSeconds(10)).isAllowed()).isTrue();
        assertThat(limiter.isAllowed(route, id).block(Duration.ofSeconds(10)).isAllowed()).isFalse();
        redisTemplate.delete(key).block();
    }

    @Test
    void gatewayUsesTheClusterFactoryForReactiveOperations() {
        assertThat(connectionFactory.isClusterAware()).isTrue();
        assertThat(connectionFactory.isUseSsl()).isTrue();
        assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
    }
}
