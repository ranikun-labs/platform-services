package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.redis.RedisOAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void savesJsonStateUnderScopedKeyAndConsumesWithAtomicGetDelete() throws Exception {
        OAuthStateRecord record = new OAuthStateRecord(
                "kakao", URI.create("https://app.example.test/callback"), "/home",
                "verifier", null, Instant.parse("2026-08-28T00:00:00Z"));

        store.save("state-value", record, Duration.ofMinutes(5));
        verify(valueOperations).set(anyString(), org.mockito.ArgumentMatchers.contains("\"provider\":\"kakao\""),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));

        String json = JsonMapper.builder().build().writeValueAsString(record);
        when(valueOperations.getAndDelete("oauth:state:state-value")).thenReturn(json);
        assertThat(store.consume("state-value")).contains(record);
        verify(valueOperations).getAndDelete("oauth:state:state-value");
    }

    @Test
    void missingStateIsEmptyAndRedisFailureIsTyped() {
        when(valueOperations.getAndDelete("oauth:state:missing")).thenReturn(null);
        assertThat(store.consume("missing")).isEmpty();

        when(valueOperations.getAndDelete("oauth:state:down"))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatThrownBy(() -> store.consume("down"))
                .isInstanceOf(OAuthStateStoreUnavailableException.class)
                .hasMessage("OAuth state store is unavailable");
    }
}
