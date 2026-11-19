package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

/** Wires Identity's Redis endpoint without changing the shared Gateway keyspace. */
@Configuration(proxyBeanMethods = false)
public class IdentityRedisConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    RedisConnectionFactory identityRedisConnectionFactory(IdentityRuntimeProperties properties) {
        var client = LettuceClientConfiguration.builder();
        if (properties.redisTlsEnabled()) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort()), client.build());
    }
}
