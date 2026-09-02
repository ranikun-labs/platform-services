package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Runtime settings for the Kakao REST Login provider.
 *
 * <p>Keys live under the {@code oauth.} namespace this module already owns for OAuth runtime
 * configuration ({@code oauth.redirect-uris}, {@code oauth.state.ttl}), keeping Shared Identity
 * free of a product-specific namespace. Endpoints default to Kakao's published hosts because they
 * are public constants; the credentials have no default at all, so an unconfigured deployment
 * cannot silently talk to Kakao with the wrong identity.
 */
@Validated
@ConfigurationProperties("oauth.kakao")
public record KakaoOAuthProperties(
        @NotBlank String clientId,
        String clientSecret,
        @DefaultValue("true") boolean clientSecretEnabled,
        @DefaultValue("https://kauth.kakao.com/oauth/authorize") String authorizationUri,
        @DefaultValue("https://kauth.kakao.com/oauth/token") String tokenUri,
        @DefaultValue("https://kapi.kakao.com/v2/user/me") String userInfoUri,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout
) {

    public KakaoOAuthProperties {
        requireAbsoluteUri("oauth.kakao.authorization-uri", authorizationUri);
        requireAbsoluteUri("oauth.kakao.token-uri", tokenUri);
        requireAbsoluteUri("oauth.kakao.user-info-uri", userInfoUri);
        requirePositive("oauth.kakao.connect-timeout", connectTimeout);
        requirePositive("oauth.kakao.read-timeout", readTimeout);
        if (clientSecretEnabled && (clientSecret == null || clientSecret.isBlank())) {
            throw new IllegalArgumentException(
                    "oauth.kakao.client-secret must be configured while oauth.kakao.client-secret-enabled is true");
        }
    }

    /** The secret is sent only when the Kakao application has Client Secret turned on. */
    public boolean sendsClientSecret() {
        return clientSecretEnabled && clientSecret != null && !clientSecret.isBlank();
    }

    private static void requireAbsoluteUri(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be an absolute http(s) URL");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " must be an absolute http(s) URL");
        }
        String scheme = uri.getScheme();
        boolean usable = uri.isAbsolute()
                && uri.getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!usable) {
            throw new IllegalArgumentException(key + " must be an absolute http(s) URL");
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be a positive duration");
        }
    }

    /** Credentials are never rendered, including into configuration diagnostics. */
    @Override
    public String toString() {
        return "KakaoOAuthProperties[clientId=REDACTED, clientSecret=REDACTED"
                + ", clientSecretEnabled=" + clientSecretEnabled
                + ", authorizationUri=" + authorizationUri
                + ", tokenUri=" + tokenUri
                + ", userInfoUri=" + userInfoUri
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
