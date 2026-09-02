package com.ranikunlabs.platform.identity.adapter.productclaims;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Runtime settings for the private Carelog Product claims read API.
 *
 * <p>There is deliberately no usable default: an unconfigured base URL or service token must stop
 * startup rather than silently produce an unauthenticated or misdirected call.
 */
@Validated
@ConfigurationProperties(prefix = "platform.identity.product-claims")
public record CarelogProductClaimsProperties(
        @NotBlank String baseUrl,
        @NotBlank String serviceToken,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout
) {

    public CarelogProductClaimsProperties {
        if (baseUrl != null && !baseUrl.isBlank()) {
            requireAbsoluteHttpUri(baseUrl);
        }
    }

    public URI baseUri() {
        return requireAbsoluteHttpUri(baseUrl);
    }

    private static URI requireAbsoluteHttpUri(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "platform.identity.product-claims.base-url must be a valid absolute URL");
        }
        String scheme = uri.getScheme();
        boolean usableHttpUri = uri.isAbsolute()
                && uri.getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!usableHttpUri) {
            throw new IllegalArgumentException(
                    "platform.identity.product-claims.base-url must be an absolute http(s) URL");
        }
        return uri;
    }

    /** Never rendered into logs or responses; the token is a credential, not diagnostic data. */
    @Override
    public String toString() {
        return "CarelogProductClaimsProperties[baseUrl=" + baseUrl
                + ", serviceToken=REDACTED, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
