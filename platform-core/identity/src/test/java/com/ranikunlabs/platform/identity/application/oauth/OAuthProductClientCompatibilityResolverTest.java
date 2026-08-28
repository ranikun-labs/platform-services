package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthProductClientCompatibilityResolverTest {

    @Mock
    private ProductClientReader productClientReader;

    private OAuthProductClientCompatibilityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OAuthProductClientCompatibilityResolver(productClientReader);
    }

    @Test
    void defaultsToTheCarelogWebClientWhenNoClientIdIsProvided() {
        RegisteredProductClient client = new RegisteredProductClient("carelog-web", Product.CARELOG, ProductClientChannel.WEB);
        when(productClientReader.requireEnabled("carelog-web")).thenReturn(client);

        RegisteredProductClient resolved = resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x"));

        assertThat(resolved).isEqualTo(client);
    }

    @Test
    void defaultsToTheCarelogMobileClientWhenNoClientIdIsProvided() {
        RegisteredProductClient client = new RegisteredProductClient("carelog-mobile", Product.CARELOG, ProductClientChannel.MOBILE);
        when(productClientReader.requireEnabled("carelog-mobile")).thenReturn(client);

        RegisteredProductClient resolved = resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.MOBILE, "/x"));

        assertThat(resolved).isEqualTo(client);
    }

    @Test
    void invalidClientIdPropagatesTheExistingTypedReason() {
        when(productClientReader.requireEnabled("bad id"))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID));

        assertThatThrownBy(() -> resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x", "bad id")))
                .isInstanceOf(ProductClientLookupException.class)
                .satisfies(e -> assertThat(((ProductClientLookupException) e).reason())
                        .isEqualTo(ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID));
    }

    @Test
    void unknownClientPropagatesTheExistingTypedReason() {
        when(productClientReader.requireEnabled("unknown"))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));

        assertThatThrownBy(() -> resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x", "unknown")))
                .isInstanceOf(ProductClientLookupException.class)
                .satisfies(e -> assertThat(((ProductClientLookupException) e).reason())
                        .isEqualTo(ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));
    }

    @Test
    void disabledClientPropagatesTheExistingTypedReason() {
        when(productClientReader.requireEnabled("disabled"))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT));

        assertThatThrownBy(() -> resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x", "disabled")))
                .isInstanceOf(ProductClientLookupException.class)
                .satisfies(e -> assertThat(((ProductClientLookupException) e).reason())
                        .isEqualTo(ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT));
    }

    @Test
    void nonCarelogProductFailsClosedAsChannelMismatch() {
        RegisteredProductClient client = new RegisteredProductClient("finance-web", Product.FINANCE_HARNESS, ProductClientChannel.WEB);
        when(productClientReader.requireEnabled("finance-web")).thenReturn(client);

        assertThatThrownBy(() -> resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x", "finance-web")))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING));
    }

    @Test
    void channelMismatchBetweenRequestAndRegisteredClientFailsClosed() {
        RegisteredProductClient client = new RegisteredProductClient("carelog-mobile", Product.CARELOG, ProductClientChannel.MOBILE);
        when(productClientReader.requireEnabled("carelog-mobile")).thenReturn(client);

        assertThatThrownBy(() -> resolver.resolve(new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/x", "carelog-mobile")))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING));
    }
}
