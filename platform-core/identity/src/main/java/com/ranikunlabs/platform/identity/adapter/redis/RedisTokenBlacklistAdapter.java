package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis writer preserving the Gateway-compatible blacklist key/value/TTL contract. */
@Component
public class RedisTokenBlacklistAdapter implements TokenBlacklistPort {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklistAdapter.class);

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBlacklistAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void addToBlacklist(String token, Duration ttl) {
        String key = "blacklist:" + token;
        redisTemplate.opsForValue().set(key, "1", ttl);
        log.info("Blacklist registration completed - key length: {}, ttl: {}", key.length(), ttl);
    }
}
