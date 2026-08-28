package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.springframework.stereotype.Component;

/**
 * Resolves the existing Kakao-era clientChannel request field to its default Carelog product
 * client when no explicit clientId is supplied. Public request shape is preserved as-is.
 */
@Component
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
            throw new OAuthAuthorizationRequestException(
                    OAuthAuthorizationRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING);
        }
        return client;
    }

    private String resolveDefaultClientId(ClientChannel channel) {
        return switch (channel) {
            case WEB -> DEFAULT_CARELOG_WEB_CLIENT_ID;
            case MOBILE -> DEFAULT_CARELOG_MOBILE_CLIENT_ID;
        };
    }

    private ProductClientChannel resolveExpectedChannel(ClientChannel channel) {
        return switch (channel) {
            case WEB -> ProductClientChannel.WEB;
            case MOBILE -> ProductClientChannel.MOBILE;
        };
    }
}
