package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAuthorizationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
    private static final RegisteredProductClient CLIENT =
            new RegisteredProductClient("carelog-web", Product.CARELOG, ProductClientChannel.WEB);
    private static final URI REDIRECT_URI = URI.create("https://carelog.example/oauth/kakao/callback");

    @Mock
    private OAuthProviderRegistry providerRegistry;
    @Mock
    private OAuthRedirectUriResolver redirectUriResolver;
    @Mock
    private OAuthProductClientCompatibilityResolver productClientCompatibilityResolver;
    @Mock
    private ReturnToValidator returnToValidator;
    @Mock
    private OAuthStateStore stateStore;
    @Mock
    private OAuthProviderPort provider;

    private final Environment environment = new MockEnvironment();

    private OAuthAuthorizationService service;
    private OAuthAuthorizationCommand command;

    @BeforeEach
    void setUp() {
        service = new OAuthAuthorizationService(
                providerRegistry, redirectUriResolver, productClientCompatibilityResolver,
                returnToValidator, stateStore, environment, CLOCK);
        command = new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/dashboard", "carelog-web");
    }

    @Test
    void validRequestGeneratesStateStoresRecordAndReturnsAuthorizationUrl() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
        when(redirectUriResolver.resolve("kakao", CLIENT)).thenReturn(REDIRECT_URI);
        when(returnToValidator.validate("/dashboard")).thenReturn("/dashboard");
        when(provider.supportsPkce()).thenReturn(true);
        when(provider.requiresNonce()).thenReturn(false);
        URI authorizationUrl = URI.create("https://kauth.kakao.com/oauth/authorize?...");
        when(provider.buildAuthorizationUrl(any())).thenReturn(authorizationUrl);

        OAuthAuthorizationService.AuthorizationUrlResult result = service.startAuthorization(command);

        assertThat(result.authorizationUrl()).isEqualTo(authorizationUrl);
        assertThat(result.state()).isNotBlank();

        ArgumentCaptor<OAuthStateRecord> recordCaptor = ArgumentCaptor.forClass(OAuthStateRecord.class);
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(stateStore).save(stateCaptor.capture(), recordCaptor.capture(), eq(Duration.ofMinutes(5)));
        assertThat(stateCaptor.getValue()).isEqualTo(result.state());
        OAuthStateRecord saved = recordCaptor.getValue();
        assertThat(saved.provider()).isEqualTo("kakao");
        assertThat(saved.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(saved.returnTo()).isEqualTo("/dashboard");
        assertThat(saved.codeVerifier()).isNotBlank();
        assertThat(saved.nonce()).isNull();
        assertThat(saved.issuedAt()).isEqualTo(Instant.now(CLOCK));

        ArgumentCaptor<OAuthAuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(OAuthAuthorizationRequest.class);
        verify(provider).buildAuthorizationUrl(requestCaptor.capture());
        OAuthAuthorizationRequest request = requestCaptor.getValue();
        assertThat(request.provider()).isEqualTo("kakao");
        assertThat(request.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(request.state()).isEqualTo(result.state());
        assertThat(request.codeChallenge()).isNotBlank();
        assertThat(request.codeChallengeMethod()).isEqualTo("S256");
        assertThat(request.nonce()).isNull();
    }

    @Test
    void providerNotRequiringPkceOmitsCodeVerifierAndChallenge() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
        when(redirectUriResolver.resolve("kakao", CLIENT)).thenReturn(REDIRECT_URI);
        when(returnToValidator.validate("/dashboard")).thenReturn("/dashboard");
        when(provider.supportsPkce()).thenReturn(false);
        when(provider.requiresNonce()).thenReturn(false);
        when(provider.buildAuthorizationUrl(any())).thenReturn(URI.create("https://provider.example/authorize"));

        service.startAuthorization(command);

        ArgumentCaptor<OAuthStateRecord> recordCaptor = ArgumentCaptor.forClass(OAuthStateRecord.class);
        verify(stateStore).save(anyString(), recordCaptor.capture(), any());
        assertThat(recordCaptor.getValue().codeVerifier()).isNull();

        ArgumentCaptor<OAuthAuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(OAuthAuthorizationRequest.class);
        verify(provider).buildAuthorizationUrl(requestCaptor.capture());
        assertThat(requestCaptor.getValue().codeChallenge()).isNull();
        assertThat(requestCaptor.getValue().codeChallengeMethod()).isNull();
    }

    @Test
    void invalidClientFailsClosedBeforeResolvingProvider() {
        when(productClientCompatibilityResolver.resolve(command))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(ProductClientLookupException.class)
                .satisfies(e -> assertThat(((ProductClientLookupException) e).reason())
                        .isEqualTo(ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID));

        verifyNoInteractions(providerRegistry, redirectUriResolver, returnToValidator, stateStore);
    }

    @Test
    void unknownClientFailsClosed() {
        when(productClientCompatibilityResolver.resolve(command))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(ProductClientLookupException.class);
        verifyNoInteractions(stateStore);
    }

    @Test
    void disabledClientFailsClosed() {
        when(productClientCompatibilityResolver.resolve(command))
                .thenThrow(new ProductClientLookupException(ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(ProductClientLookupException.class);
        verifyNoInteractions(stateStore);
    }

    @Test
    void unsupportedProviderFailsClosedAfterClientValidation() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao"))
                .thenThrow(new OAuthAuthorizationRequestException(
                        OAuthAuthorizationRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER));

        verifyNoInteractions(redirectUriResolver, returnToValidator, stateStore);
    }

    @Test
    void unsupportedRedirectCombinationFailsClosedBeforeStateIsGenerated() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
        when(redirectUriResolver.resolve("kakao", CLIENT))
                .thenThrow(new OAuthAuthorizationRequestException(
                        OAuthAuthorizationRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(OAuthAuthorizationRequestException.class);

        verifyNoInteractions(returnToValidator, stateStore);
    }

    @Test
    void invalidReturnToFailsClosedBeforeStateIsPersisted() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
        when(redirectUriResolver.resolve("kakao", CLIENT)).thenReturn(REDIRECT_URI);
        when(returnToValidator.validate("/dashboard"))
                .thenThrow(new OAuthAuthorizationRequestException(
                        OAuthAuthorizationRequestException.Reason.INVALID_OAUTH_RETURN_TO));

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(OAuthAuthorizationRequestException.class);

        verifyNoInteractions(stateStore);
        verify(provider, never()).buildAuthorizationUrl(any());
    }

    @Test
    void stateStoreFailurePropagatesAndSkipsProviderUrlGeneration() {
        when(productClientCompatibilityResolver.resolve(command)).thenReturn(CLIENT);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
        when(redirectUriResolver.resolve("kakao", CLIENT)).thenReturn(REDIRECT_URI);
        when(returnToValidator.validate("/dashboard")).thenReturn("/dashboard");
        when(provider.supportsPkce()).thenReturn(true);
        when(provider.requiresNonce()).thenReturn(false);
        org.mockito.Mockito.doThrow(new OAuthStateStoreUnavailableException("redis down", new RuntimeException()))
                .when(stateStore).save(anyString(), any(), any());

        assertThatThrownBy(() -> service.startAuthorization(command))
                .isInstanceOf(OAuthStateStoreUnavailableException.class);

        verify(provider, never()).buildAuthorizationUrl(any());
    }
}
