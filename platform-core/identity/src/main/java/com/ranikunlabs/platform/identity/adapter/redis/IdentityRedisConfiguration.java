package com.ranikunlabs.platform.identity.adapter.redis;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
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
            String clusterNodes, ConfigurableEnvironment environment) {
        String username = rawCredential(environment, "platform.identity.redis-username", "PLATFORM_IDENTITY_REDIS_USERNAME");
        String password = rawCredential(environment, "platform.identity.redis-password", "PLATFORM_IDENTITY_REDIS_PASSWORD");
        if ((username != null && username.isBlank()) || (password != null && password.isBlank())) {
            throw new IllegalArgumentException("Redis credentials must not be blank");
        }
        if (username != null && password == null) {
            throw new IllegalArgumentException("Redis username requires a password");
        }
        var client = LettuceClientConfiguration.builder();
        if (properties.redisTlsEnabled()) {
            client.useSsl();
        }
        if (clusterNodes != null) {
            if (clusterNodes.isBlank()) {
                throw new IllegalArgumentException("Redis cluster nodes must not be blank");
            }
            var cluster = new RedisClusterConfiguration(Arrays.stream(clusterNodes.split(","))
                    .map(String::trim).toList());
            if (username != null) cluster.setUsername(username);
            if (password != null) cluster.setPassword(RedisPassword.of(password));
            return new LettuceConnectionFactory(cluster, client.build());
        }
        var standalone = new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort());
        if (username != null) standalone.setUsername(username);
        if (password != null) standalone.setPassword(RedisPassword.of(password));
        return new LettuceConnectionFactory(standalone, client.build());
    }
    private static String rawCredential(ConfigurableEnvironment environment, String property, String envVariable) {
        for (var source : environment.getPropertySources()) {
            Object value = source.getProperty(property);
            if (value == null) value = source.getProperty(envVariable);
            if (value != null) return value.toString();
        }
        return null;
    }
}
