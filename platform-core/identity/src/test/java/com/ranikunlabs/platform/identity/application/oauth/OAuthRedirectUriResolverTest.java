package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRedirectUriResolverTest {

    @Test
    void resolvesConfiguredCallbackForRegisteredClientId() {
        OAuthProviderCallbackProperties properties = new OAuthProviderCallbackProperties();
        properties.setProviderCallbacks(java.util.Map.of(
                "kakao", java.util.Map.of("carelog-web", "https://carelog.example/oauth/kakao/callback")));
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(properties, new MockEnvironment());
        RegisteredProductClient client = new RegisteredProductClient("carelog-web", Product.CARELOG, ProductClientChannel.WEB);

        URI redirectUri = resolver.resolve("kakao", client);

        assertThat(redirectUri).isEqualTo(URI.create("https://carelog.example/oauth/kakao/callback"));
    }

    @Test
    void fallsBackToLegacyChannelPropertyForTheDefaultCarelogWebClient() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("oauth.redirect-uris.kakao.WEB", "https://carelog.example/legacy/callback");
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(new OAuthProviderCallbackProperties(), environment);
        RegisteredProductClient client = new RegisteredProductClient("carelog-web", Product.CARELOG, ProductClientChannel.WEB);

        URI redirectUri = resolver.resolve("kakao", client);

        assertThat(redirectUri).isEqualTo(URI.create("https://carelog.example/legacy/callback"));
    }

    @Test
    void missingClientIdFailsClosed() {
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(new OAuthProviderCallbackProperties(), new MockEnvironment());

        assertThatThrownBy(() -> resolver.resolve("kakao", new RegisteredProductClient(null, Product.CARELOG, ProductClientChannel.WEB)))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL));
    }

    @Test
    void unconfiguredCombinationFailsClosed() {
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(new OAuthProviderCallbackProperties(), new MockEnvironment());
        RegisteredProductClient client = new RegisteredProductClient("some-other-client", Product.CARELOG, ProductClientChannel.WEB);

        assertThatThrownBy(() -> resolver.resolve("kakao", client))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void relativeConfiguredUriFailsClosedToPreventAnOpenRedirect() {
        OAuthProviderCallbackProperties properties = new OAuthProviderCallbackProperties();
        properties.setProviderCallbacks(java.util.Map.of("kakao", java.util.Map.of("carelog-web", "/relative/callback")));
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(properties, new MockEnvironment());
        RegisteredProductClient client = new RegisteredProductClient("carelog-web", Product.CARELOG, ProductClientChannel.WEB);

        assertThatThrownBy(() -> resolver.resolve("kakao", client))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }
}
