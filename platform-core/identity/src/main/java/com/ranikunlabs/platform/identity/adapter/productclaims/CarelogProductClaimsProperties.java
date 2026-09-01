package com.ranikunlabs.platform.identity.adapter.productclaims;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/** Validated, opt-in settings for the Carelog Product claims HTTP adapter. */
@Validated
@ConfigurationProperties(prefix = "platform.identity.product-claims")
public class CarelogProductClaimsProperties {

    private boolean enabled;

    @NotBlank
    private String baseUrl;

    private String serviceSecret;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(1);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getServiceSecret() {
        return serviceSecret;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setServiceSecret(String serviceSecret) {
        this.serviceSecret = serviceSecret;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = requirePositiveTimeout("connect-timeout", connectTimeout);
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = requirePositiveTimeout("read-timeout", readTimeout);
    }

    public URI requiredBaseUri() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "platform.identity.product-claims.base-url must be provided");
        }

        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "platform.identity.product-claims.base-url must be a valid absolute URI");
        }

        String path = uri.getPath();
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isEmpty())) {
            throw new IllegalStateException(
                    "platform.identity.product-claims.base-url must be a service origin");
        }
        return uri;
    }

    public String requiredServiceSecret() {
        if (serviceSecret == null || serviceSecret.isBlank()) {
            throw new IllegalStateException(
                    "platform.identity.product-claims.service-secret must be provided and at least 32 characters");
        }
        if (serviceSecret.length() < 32) {
            throw new IllegalStateException(
                    "platform.identity.product-claims.service-secret must be at least 32 characters");
        }
        return serviceSecret;
    }

    private Duration requirePositiveTimeout(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "platform.identity.product-claims." + property + " must be positive");
        }
        return value;
    }
}
