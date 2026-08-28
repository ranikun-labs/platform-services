package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenBlacklistAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisTokenBlacklistAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisTokenBlacklistAdapter(redisTemplate);
    }

    @Test
    void writesRawTokenWithGatewayCompatibleKeyValueAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        adapter.addToBlacklist("raw-access-token", Duration.ofMinutes(10));

        verify(valueOperations).set("blacklist:raw-access-token", "1", Duration.ofMinutes(10));
    }
}
