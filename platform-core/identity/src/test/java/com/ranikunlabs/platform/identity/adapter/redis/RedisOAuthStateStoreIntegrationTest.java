package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves RedisOAuthStateStore's exact key/serialization/GETDEL/TTL contract against a real Redis
 * server, matching the canonical Carelog integration coverage. No new production dependency is
 * introduced; the generic Testcontainers container already used for Postgres is reused here.
 */
class RedisOAuthStateStoreIntegrationTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisOAuthStateStore store;

    @BeforeAll
    static void verifyContainerRunning() {
        assertThat(REDIS.isRunning()).isTrue();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new RedisOAuthStateStore(redisTemplate, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void savesUnderTheExactOAuthStateKeyPrefixAndRoundTripsTheRecord() {
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://app.example.test/callback"), "/home",
                "verifier-value", "nonce-value", Instant.parse("2026-08-28T00:00:00Z"));

        store.save("real-redis-state", record, Duration.ofMinutes(5));

        assertThat(redisTemplate.hasKey("oauth:state:real-redis-state")).isTrue();
        assertThat(store.consume("real-redis-state")).contains(record);
    }

    @Test
    void getAndDeleteConsumesTheStateExactlyOnce() {
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://app.example.test/callback"), "/home",
                "verifier-value", null, Instant.now());
        store.save("one-time-state", record, Duration.ofMinutes(5));

        Optional<OAuthStateRecord> first = store.consume("one-time-state");
        Optional<OAuthStateRecord> replay = store.consume("one-time-state");

        assertThat(first).contains(record);
        assertThat(replay).isEmpty();
        assertThat(redisTemplate.hasKey("oauth:state:one-time-state")).isFalse();
    }

    @Test
    void missingStateConsumesToEmptyWithoutThrowing() {
        assertThat(store.consume("never-saved")).isEmpty();
    }

    @Test
    void stateExpiresAfterItsRealRedisTtlElapses() throws InterruptedException {
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://app.example.test/callback"), "/home",
                "verifier-value", null, Instant.now());
        store.save("short-lived-state", record, Duration.ofSeconds(1));
        assertThat(redisTemplate.hasKey("oauth:state:short-lived-state")).isTrue();

        // Bounded poll for real Redis expiry rather than a fixed sleep, keeping the assertion tied
        // to actual server-side TTL behavior instead of a guessed wall-clock delay.
        boolean expired = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            if (Boolean.FALSE.equals(redisTemplate.hasKey("oauth:state:short-lived-state"))) {
                expired = true;
                break;
            }
            Thread.sleep(200);
        }

        assertThat(expired).as("key should expire from real Redis TTL within the poll window").isTrue();
        assertThat(store.consume("short-lived-state")).isEmpty();
    }

    @Test
    void malformedStoredPayloadIsSurfacedAsStoreUnavailableNotAsAMissingState() {
        redisTemplate.opsForValue().set("oauth:state:malformed", "{not-valid-json", Duration.ofMinutes(5));

        assertThatThrownBy(() -> store.consume("malformed"))
                .isInstanceOf(OAuthStateStoreUnavailableException.class);
    }

    @Test
    void unreachableRedisIsSurfacedAsStoreUnavailableNotAsAnUncaughtInfrastructureException() {
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://app.example.test/callback"), "/home", "verifier", null, Instant.now());
        // Point at a real, closed port (nothing bound there) to trigger the same
        // RedisConnectionFailureException production sees when the real server is unreachable,
        // rather than a Spring-lifecycle artifact like a destroyed connection factory.
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), findClosedPort()));
        unreachable.afterPropertiesSet();
        RedisOAuthStateStore unreachableStore =
                new RedisOAuthStateStore(new StringRedisTemplate(unreachable), JsonMapper.builder().build());
        try {
            assertThatThrownBy(() -> unreachableStore.save("unreachable", record, Duration.ofMinutes(5)))
                    .isInstanceOf(OAuthStateStoreUnavailableException.class);
        } finally {
            unreachable.destroy();
        }
    }

    private static int findClosedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not find a closed port for the unreachable-Redis test", e);
        }
    }
}
