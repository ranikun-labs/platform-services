package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthProviderRegistryTest {

    @Test
    void resolvesARegisteredProviderCaseInsensitively() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of(new StubProvider("kakao")));

        assertThat(registry.resolve("KAKAO")).isInstanceOf(StubProvider.class);
        assertThat(registry.resolve(" Kakao ")).isInstanceOf(StubProvider.class);
    }

    @Test
    void unsupportedProviderFailsClosedWithTypedReason() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of(new StubProvider("kakao")));

        assertThatThrownBy(() -> registry.resolve("google"))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER));
    }

    @Test
    void emptyRegistryFailsClosedForAnyProvider() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.resolve("kakao"))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void duplicateProviderRegistrationFailsFast() {
        assertThatThrownBy(() -> new OAuthProviderRegistry(List.of(new StubProvider("kakao"), new StubProvider("KAKAO"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private record StubProvider(String providerCode) implements OAuthProviderPort {
        @Override
        public URI buildAuthorizationUrl(OAuthAuthorizationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OAuthTokenGrant exchangeCode(String authorizationCode, URI redirectUri, String codeVerifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OAuthPrincipal fetchPrincipal(OAuthTokenGrant grant, OAuthStateRecord state) {
            throw new UnsupportedOperationException();
        }
    }
}
