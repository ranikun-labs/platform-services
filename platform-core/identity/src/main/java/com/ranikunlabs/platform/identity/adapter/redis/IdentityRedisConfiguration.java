package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.util.Arrays;

/** Wires Identity's Redis endpoint without changing the shared Gateway keyspace. */
@Configuration(proxyBeanMethods = false)
public class IdentityRedisConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    RedisConnectionFactory identityRedisConnectionFactory(IdentityRuntimeProperties properties,
            @Value("${platform.identity.redis-cluster-nodes:${PLATFORM_IDENTITY_REDIS_CLUSTER_NODES:#{null}}}")
            String clusterNodes) {
        var client = LettuceClientConfiguration.builder();
        if (properties.redisTlsEnabled()) {
            client.useSsl();
        }
        if (clusterNodes != null) {
            if (clusterNodes.isBlank()) {
                throw new IllegalArgumentException("Redis cluster nodes must not be blank");
            }
            return new LettuceConnectionFactory(
                    new RedisClusterConfiguration(Arrays.stream(clusterNodes.split(","))
                            .map(String::trim).toList()), client.build());
        }
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort()), client.build());
    }
}
