package com.ranikunlabs.platform.core.observability;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlatformMetricsConfiguration {

    private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("local", "test", "dev", "staging", "prod");

    @Bean
    MeterFilter boundedPlatformMetricTags(
            @Value("${platform.metrics.environment:local}") String environment) {
        return MeterFilter.commonTags(Tags.of(
                "service", "platform-core",
                "environment", boundedEnvironment(environment)));
    }

    @Bean
    MeterFilter removeSensitiveHttpMetricTags() {
        return MeterFilter.ignoreTags(
                "uri", "url", "path", "raw_uri", "raw_url", "rawUri", "rawUrl",
                "request_uri", "request_url", "requestUri", "requestUrl",
                "query", "query_string", "queryString", "user", "user_id", "userId",
                "user-id", "account", "account_id", "accountId", "account-id",
                "tenant", "tenant_id", "tenantId", "tenant-id", "jwt", "access_token",
                "access-token", "token", "authorization", "exception", "error");
    }

    private String boundedEnvironment(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_ENVIRONMENTS.contains(normalized) ? normalized : "unknown";
    }
}
