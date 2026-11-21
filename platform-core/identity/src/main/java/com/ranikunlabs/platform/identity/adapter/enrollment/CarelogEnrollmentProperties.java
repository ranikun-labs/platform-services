package com.ranikunlabs.platform.identity.adapter.enrollment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "platform.identity.carelog-enrollment")
public class CarelogEnrollmentProperties {
    private String baseUrl;
    private String credential;
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(2);

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setCredential(String credential) { this.credential = credential; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getBaseUrl() { return baseUrl; }
    public String getCredential() { return credential; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }

    URI requiredBaseUri() {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("enrollment base-url must be a valid service origin");
        }
    }

    String requiredCredential() {
        if (credential == null || credential.isBlank() || credential.length() < 32
                || credential.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("enrollment bearer credential must be configured");
        }
        return credential;
    }

    Duration requiredConnectTimeout() { return positive(connectTimeout); }
    Duration requiredReadTimeout() { return positive(readTimeout); }
    private Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("enrollment timeout must be positive");
        }
        return value;
    }
}
