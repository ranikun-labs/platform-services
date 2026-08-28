package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/** Redis-backed OAuth state store that consumes state exactly once via an atomic GETDEL. */
@Component
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
        } catch (JacksonException | RedisConnectionFailureException | QueryTimeoutException e) {
            throw unavailable(e);
        } catch (DataAccessException e) {
            throw unavailable(e);
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
        } catch (JacksonException | RedisConnectionFailureException | QueryTimeoutException e) {
            throw unavailable(e);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private OAuthStateStoreUnavailableException unavailable(Exception cause) {
        return new OAuthStateStoreUnavailableException("OAuth state store is unavailable", cause);
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }
}
