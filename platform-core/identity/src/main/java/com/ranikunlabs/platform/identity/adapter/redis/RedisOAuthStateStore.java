package com.ranikunlabs.platform.identity.adapter.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/** Redis-backed one-time OAuth state using an atomic GETDEL consume operation. */
public class RedisOAuthStateStore implements OAuthStateStore {

    private static final String KEY_PREFIX = "oauth:state:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisOAuthStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String state, OAuthStateRecord record, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(state), objectMapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException | RedisConnectionFailureException | QueryTimeoutException exception) {
            throw unavailable(exception);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public Optional<OAuthStateRecord> consume(String state) {
        try {
            String value = redisTemplate.opsForValue().getAndDelete(key(state));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, OAuthStateRecord.class));
        } catch (JsonProcessingException | RedisConnectionFailureException | QueryTimeoutException exception) {
            throw unavailable(exception);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private OAuthStateStoreUnavailableException unavailable(Exception cause) {
        return new OAuthStateStoreUnavailableException("OAuth state store is unavailable", cause);
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }
}
