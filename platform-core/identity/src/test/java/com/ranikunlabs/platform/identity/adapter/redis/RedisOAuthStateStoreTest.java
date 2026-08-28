package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisOAuthStateStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisOAuthStateStore store;

    @BeforeEach
    void setUp() {
        store = new RedisOAuthStateStore(redisTemplate, JsonMapper.builder().build());
    }

    @Test
    void savesUnderTheExactOAuthStateKeyPrefixWithTheGivenTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://carelog.example/callback"), "/dashboard", "verifier", null,
                Instant.parse("2026-08-28T00:00:00Z"));

        store.save("abc123", record, Duration.ofMinutes(5));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        assertThat(keyCaptor.getValue()).isEqualTo("oauth:state:abc123");
        assertThat(valueCaptor.getValue()).contains("kakao").contains("verifier").contains("/dashboard");
    }

    @Test
    void roundTripsTheRecordThroughSaveAndConsume() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://carelog.example/callback"), "/dashboard", "verifier", "nonce-value",
                Instant.parse("2026-08-28T00:00:00Z"));
        store.save("abc123", record, Duration.ofMinutes(5));
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("oauth:state:abc123"), valueCaptor.capture(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(valueOperations.getAndDelete("oauth:state:abc123")).thenReturn(valueCaptor.getValue());

        Optional<OAuthStateRecord> consumed = store.consume("abc123");

        assertThat(consumed).contains(record);
    }

    @Test
    void consumeUsesAtomicGetAndDeleteUnderTheSameKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:abc123")).thenReturn(null);

        Optional<OAuthStateRecord> consumed = store.consume("abc123");

        assertThat(consumed).isEmpty();
        verify(valueOperations).getAndDelete("oauth:state:abc123");
    }

    @Test
    void missingStateReturnsEmptyRatherThanThrowing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:missing")).thenReturn(null);

        assertThat(store.consume("missing")).isEmpty();
    }

    @Test
    void secondConsumeOfTheSameStateFindsNothingBecauseGetAndDeleteAlreadyRemovedIt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:abc123")).thenReturn(null);

        assertThat(store.consume("abc123")).isEmpty();
        assertThat(store.consume("abc123")).isEmpty();
    }

    @Test
    void malformedStoredPayloadIsTreatedAsAStoreOutageNotAMissingState() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:abc123")).thenReturn("{not-json");

        assertThatThrownBy(() -> store.consume("abc123"))
                .isInstanceOf(OAuthStateStoreUnavailableException.class);
    }

    @Test
    void redisFailureOnSaveIsSurfacedAsStoreUnavailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://carelog.example/callback"), "/dashboard", "verifier", null, Instant.now());

        assertThatThrownBy(() -> store.save("abc123", record, Duration.ofMinutes(5)))
                .isInstanceOf(OAuthStateStoreUnavailableException.class);
    }

    @Test
    void redisFailureOnConsumeIsSurfacedAsStoreUnavailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:abc123")).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> store.consume("abc123"))
                .isInstanceOf(OAuthStateStoreUnavailableException.class);
    }
}
