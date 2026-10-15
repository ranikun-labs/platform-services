package com.ranikunlabs.platform.gateway.config

import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.config.MeterFilter
import java.util.Locale
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class PlatformMetricsConfiguration {

    @Bean
    fun boundedPlatformMetricTags(
        @Value("\${platform.metrics.environment:local}") environment: String
    ): MeterFilter = MeterFilter.commonTags(
        Tags.of("service", "gateway", "environment", boundedEnvironment(environment))
    )

    @Bean
    fun removeSensitiveHttpMetricTags(): MeterFilter = MeterFilter.ignoreTags(
        "uri", "url", "path", "raw_uri", "raw_url", "rawUri", "rawUrl",
        "request_uri", "request_url", "requestUri", "requestUrl",
        "query", "query_string", "queryString", "user", "user_id", "userId",
        "user-id", "account", "account_id", "accountId", "account-id",
        "tenant", "tenant_id", "tenantId", "tenant-id", "jwt", "access_token",
        "access-token", "token", "authorization", "exception", "error"
    )

    private fun boundedEnvironment(value: String): String = value.trim().lowercase(Locale.ROOT)
        .takeIf(ALLOWED_ENVIRONMENTS::contains)
        ?: "unknown"

    private companion object {
        val ALLOWED_ENVIRONMENTS = setOf("local", "test", "dev", "staging", "prod")
    }
}
