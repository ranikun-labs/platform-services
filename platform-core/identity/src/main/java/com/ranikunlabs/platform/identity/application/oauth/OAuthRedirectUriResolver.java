package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;

/** Selects the configured callback URI for a provider and product-client combination. */
@Component
public class OAuthRedirectUriResolver {

    private static final String CARELOG_WEB_CLIENT_ID = "carelog-web";
    private static final String CARELOG_MOBILE_CLIENT_ID = "carelog-mobile";

    private final OAuthProviderCallbackProperties callbackProperties;
    private final Environment environment;

    public OAuthRedirectUriResolver(OAuthProviderCallbackProperties callbackProperties, Environment environment) {
        this.callbackProperties = callbackProperties;
        this.environment = environment;
    }

    public URI resolve(String provider, RegisteredProductClient client) {
        if (client == null || client.clientId() == null || client.clientId().isBlank()) {
            throw unsupportedClientChannel();
        }

        String providerCode = OAuthProviderRegistry.normalize(provider);
        String configuredUri = callbackProperties.find(providerCode, client.clientId());
        if (configuredUri == null) {
            configuredUri = legacyConfiguredUri(providerCode, client);
        }
        if (configuredUri == null || configuredUri.isBlank()) {
            throw unsupportedClientChannel();
        }

        try {
            URI uri = URI.create(configuredUri);
            if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
                throw new IllegalArgumentException("OAuth callback URI must be absolute");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw unsupportedClientChannel();
        }
    }

    private String legacyConfiguredUri(String providerCode, RegisteredProductClient client) {
        String legacyChannel = legacyChannel(client);
        if (legacyChannel == null) {
            return null;
        }
        return environment.getProperty("oauth.redirect-uris." + providerCode + "." + legacyChannel);
    }

    private String legacyChannel(RegisteredProductClient client) {
        if (CARELOG_WEB_CLIENT_ID.equals(client.clientId()) && client.channel() == ProductClientChannel.WEB) {
            return ProductClientChannel.WEB.name();
        }
        if (CARELOG_MOBILE_CLIENT_ID.equals(client.clientId()) && client.channel() == ProductClientChannel.MOBILE) {
            return ProductClientChannel.MOBILE.name();
        }
        return null;
    }

    private OAuthAuthorizationRequestException unsupportedClientChannel() {
        return new OAuthAuthorizationRequestException(
                OAuthAuthorizationRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL);
    }
}
