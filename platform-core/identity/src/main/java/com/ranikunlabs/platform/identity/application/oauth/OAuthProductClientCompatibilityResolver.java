package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthRequestException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;

/**
 * Compatibility layer that maps the existing WEB/MOBILE request to the registered Carelog
 * product client while keeping the registry as the trust anchor.
 */
public class OAuthProductClientCompatibilityResolver {

    public static final String DEFAULT_CARELOG_WEB_CLIENT_ID = "carelog-web";
    public static final String DEFAULT_CARELOG_MOBILE_CLIENT_ID = "carelog-mobile";

    private final ProductClientReader productClientReader;

    public OAuthProductClientCompatibilityResolver(ProductClientReader productClientReader) {
        this.productClientReader = productClientReader;
    }

    public RegisteredProductClient resolve(OAuthAuthorizationCommand command) {
        String clientId = command.clientId() == null
                ? resolveDefaultClientId(command.clientChannel())
                : command.clientId();
        RegisteredProductClient client = productClientReader.requireEnabled(clientId);

        if (client.product() != Product.CARELOG
                || client.channel() != resolveExpectedChannel(command.clientChannel())) {
            throw new OAuthRequestException(
                    OAuthRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING);
        }
        return client;
    }

    private String resolveDefaultClientId(ClientChannel channel) {
        if (channel == null) {
            throw new OAuthRequestException(OAuthRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL);
        }
        return switch (channel) {
            case WEB -> DEFAULT_CARELOG_WEB_CLIENT_ID;
            case MOBILE -> DEFAULT_CARELOG_MOBILE_CLIENT_ID;
        };
    }

    private ProductClientChannel resolveExpectedChannel(ClientChannel channel) {
        if (channel == null) {
            throw new OAuthRequestException(OAuthRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL);
        }
        return switch (channel) {
            case WEB -> ProductClientChannel.WEB;
            case MOBILE -> ProductClientChannel.MOBILE;
        };
    }
}
