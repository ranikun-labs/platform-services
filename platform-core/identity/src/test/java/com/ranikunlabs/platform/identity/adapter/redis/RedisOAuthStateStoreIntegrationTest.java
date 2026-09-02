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
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the production OAuth state store against the Redis version used by the runtime topology. */
class RedisOAuthStateStoreIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(REDIS_PORT);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisOAuthStateStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new RedisOAuthStateStore(redisTemplate, JsonMapper.builder().build());
    }

    @AfterEach
    void closeRedisConnection() {
        connectionFactory.destroy();
    }

    @Test
    void savesUnderTheExactOAuthStateKey() {
        String state = "abc123";

        store.save(state, record(state), Duration.ofMinutes(5));

        assertThat(redisTemplate.hasKey(key(state))).isTrue();
        assertThat(redisTemplate.hasKey(state)).isFalse();
    }

    @Test
    void roundTripsACompletelyPopulatedRecordAsRealJson() {
        String state = uniqueState();
        OAuthStateRecord expected = record(state);

        store.save(state, expected, Duration.ofMinutes(5));

        String rawPayload = redisTemplate.opsForValue().get(key(state));
        assertThat(rawPayload)
                .isNotNull()
                .startsWith("{")
                .contains("\"provider\"", "\"redirectUri\"", "\"returnTo\"", "\"codeVerifier\"", "\"nonce\"");
        assertThat(store.consume(state)).contains(expected);
    }

    @Test
    void expiresWithAFiniteRedisTtlAndThenConsumesAsMissing() {
        String state = uniqueState();
        Duration ttl = Duration.ofSeconds(2);

        store.save(state, record(state), ttl);

        Long observedTtlMillis = redisTemplate.getExpire(key(state), TimeUnit.MILLISECONDS);
        assertThat(observedTtlMillis).isGreaterThan(0L).isLessThanOrEqualTo(ttl.toMillis());
        assertThat(observedTtlMillis).isNotEqualTo(-1L);

        awaitKeyAbsence(key(state));

        assertThat(redisTemplate.hasKey(key(state))).isFalse();
        assertThat(store.consume(state)).isEmpty();
    }

    @Test
    void getAndDeleteProvidesOneTimeConsumptionAgainstRealRedis() {
        String state = uniqueState();
        OAuthStateRecord expected = record(state);
        store.save(state, expected, Duration.ofMinutes(5));

        assertThat(store.consume(state)).contains(expected);
        assertThat(store.consume(state)).isEmpty();
        assertThat(redisTemplate.hasKey(key(state))).isFalse();
    }

    @Test
    void concurrentConsumersReceiveExactlyOneCopyOfTheStoredState() throws Exception {
        String state = uniqueState();
        OAuthStateRecord expected = record(state);
        store.save(state, expected, Duration.ofMinutes(5));

        int consumerCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(consumerCount);
        CountDownLatch ready = new CountDownLatch(consumerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<OAuthStateRecord>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < consumerCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent Redis consumers");
                    }
                    return store.consume(state);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<OAuthStateRecord>> results = new ArrayList<>();
            for (Future<Optional<OAuthStateRecord>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).hasSize(consumerCount);
            assertThat(results.stream().filter(Optional::isPresent).toList()).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty).toList()).hasSize(consumerCount - 1);
            assertThat(results.stream()
                    .filter(Optional::isPresent)
                    .findFirst()
                    .orElseThrow())
                    .contains(expected);
            assertThat(redisTemplate.hasKey(key(state))).isFalse();
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingStateReturnsEmptyFromRealRedis() {
        assertThat(store.consume(uniqueState())).isEmpty();
    }

    @Test
    void malformedPayloadIsDeletedByGetAndDeleteAndSurfacesStoreUnavailable() {
        String state = uniqueState();
        String malformedPayload = "{not-json";
        redisTemplate.opsForValue().set(key(state), malformedPayload, Duration.ofMinutes(5));

        assertThatThrownBy(() -> store.consume(state))
                .isInstanceOfSatisfying(OAuthStateStoreUnavailableException.class, exception -> {
                    assertThat(exception).hasMessage("OAuth state store is unavailable");
                    assertThat(exception.getMessage()).doesNotContain(malformedPayload);
                });
        assertThat(redisTemplate.hasKey(key(state))).isFalse();
    }

    private OAuthStateRecord record(String suffix) {
        return new OAuthStateRecord(
                "kakao",
                URI.create("https://carelog.example/callback"),
                "/dashboard/" + suffix,
                "verifier-" + suffix,
                "nonce-" + suffix,
                Instant.parse("2026-08-28T00:00:00Z"));
    }

    private String uniqueState() {
        return "redis-integration-" + UUID.randomUUID();
    }

    private String key(String state) {
        return "oauth:state:" + state;
    }

    private void awaitKeyAbsence(String key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Redis key expiry", exception);
            }
        }
    }
}
