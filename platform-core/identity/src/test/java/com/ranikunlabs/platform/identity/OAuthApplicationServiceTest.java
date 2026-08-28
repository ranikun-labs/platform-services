package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProductClientCompatibilityResolver;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderRegistry;
import com.ranikunlabs.platform.identity.application.oauth.OAuthRedirectUriResolver;
import com.ranikunlabs.platform.identity.application.oauth.ReturnToValidator;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountStatus;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountView;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthApplicationServiceTest {

    private static final URI REDIRECT = URI.create("https://app.example.test/oauth/callback");
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Test
    void authorizationGeneratesServerOnlyStateAndPkceAfterClientValidation() {
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingProvider provider = new CapturingProvider("neutral", true, true);
        OAuthAuthorizationService service = authorizationService(provider, stateStore,
                new MockEnvironment().withProperty("oauth.redirect-uris.neutral.WEB", REDIRECT.toString()));

        OAuthAuthorizationService.AuthorizationUrlResult result = service.startAuthorization(
                new OAuthAuthorizationCommand("NEUTRAL", ClientChannel.WEB, "/home"));

        assertThat(result.authorizationUrl()).isEqualTo(URI.create("https://provider.example/authorize"));
        assertThat(result.state()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(stateStore.record.codeVerifier()).hasSize(43);
        assertThat(provider.request.codeChallenge()).isNotEqualTo(stateStore.record.codeVerifier());
        assertThat(provider.request.redirectUri()).isEqualTo(REDIRECT);
        assertThat(stateStore.ttl).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void authorizationOmitsPkceAndAddsNonceOnlyAccordingToProviderCapabilities() {
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingProvider provider = new CapturingProvider("neutral", true, false);

        authorizationService(provider, stateStore,
                new MockEnvironment().withProperty("oauth.redirect-uris.neutral.WEB", REDIRECT.toString()))
                .startAuthorization(new OAuthAuthorizationCommand("neutral", ClientChannel.WEB, "/"));

        assertThat(stateStore.record.codeVerifier()).isNull();
        assertThat(provider.request.codeChallenge()).isNull();
        assertThat(stateStore.record.nonce()).hasSize(43);
        assertThat(provider.request.nonce()).isEqualTo(stateStore.record.nonce());
    }

    @Test
    void authorizationFailsClosedBeforeProviderSelectionForClientMappingOrUnknownProvider() {
        CapturingProvider provider = new CapturingProvider("neutral", false, true);
        ProductClientReader reader = clientId -> new RegisteredProductClient(
                clientId, Product.CARELOG, ProductClientChannel.WEB);
        OAuthProductClientCompatibilityResolver resolver = new OAuthProductClientCompatibilityResolver(reader);
        OAuthAuthorizationService service = new OAuthAuthorizationService(
                new OAuthProviderRegistry(List.of(provider)),
                new OAuthRedirectUriResolver(new MockEnvironment()), resolver,
                new ReturnToValidator(List.of()), new CapturingStateStore(), new MockEnvironment(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.startAuthorization(
                new OAuthAuthorizationCommand("neutral", ClientChannel.MOBILE, "/")))
                .isInstanceOf(OAuthRequestException.class)
                .satisfies(error -> assertThat(((OAuthRequestException) error).reason())
                        .isEqualTo(OAuthRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING));
        assertThatThrownBy(() -> service.startAuthorization(
                new OAuthAuthorizationCommand("missing", ClientChannel.WEB, "/")))
                .isInstanceOf(OAuthRequestException.class)
                .satisfies(error -> assertThat(((OAuthRequestException) error).reason())
                        .isEqualTo(OAuthRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER));
        assertThat(provider.request).isNull();
    }

    @Test
    void loginConsumesStateBeforeProviderAndReturnsInvalidForMissingOrMismatchedState() {
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingProvider provider = new CapturingProvider("neutral", false, true);
        OAuthLoginService service = loginService(provider, stateStore);

        assertThat(service.completeLogin(new OAuthCallbackCommand("neutral", "code", "missing")))
                .isEqualTo(new OAuthLoginResult.InvalidOrExpiredState());
        stateStore.record = new OAuthStateRecord("other", REDIRECT, "/", "verifier", null, NOW);
        assertThat(service.completeLogin(new OAuthCallbackCommand("neutral", "code", "state")))
                .isEqualTo(new OAuthLoginResult.InvalidOrExpiredState());
        assertThat(provider.exchangeCalled).isFalse();
    }

    @Test
    void loginReturnsOnboardingForUnlinkedVerifiedPrincipalWithoutWritingIdentity() {
        CapturingStateStore stateStore = new CapturingStateStore();
        stateStore.record = state();
        CapturingProvider provider = new CapturingProvider("neutral", false, true);
        provider.principal = new OAuthPrincipal("neutral", "subject", "person@example.test", true, "Person");
        OAuthLoginService service = loginService(provider, stateStore);

        OAuthLoginResult result = service.completeLogin(new OAuthCallbackCommand("neutral", "code", "state"));

        assertThat(result).isEqualTo(new OAuthLoginResult.NewAccountOnboardingRequired(
                new com.ranikunlabs.platform.identity.application.port.oauth.OnboardingCandidate(
                        "neutral", "subject", "person@example.test", true, "Person", "/home")));
        assertThat(provider.exchangeCalled).isTrue();
    }

    @Test
    void loginIssuesExistingAccountTokensOnlyAfterActiveExternalIdentityAndClaims() {
        CapturingStateStore stateStore = new CapturingStateStore();
        stateStore.record = state();
        CapturingProvider provider = new CapturingProvider("neutral", false, true);
        provider.principal = new OAuthPrincipal("neutral", "subject", null, false, null);
        ExternalIdentityLookupPort external = mock(ExternalIdentityLookupPort.class);
        CRMIdentityProjectionPort claimsPort = mock(CRMIdentityProjectionPort.class);
        AuthTokenIssuanceService tokenService = mock(AuthTokenIssuanceService.class);
        UUID accountId = UUID.randomUUID();
        CRMIdentityClaims claims = new CRMIdentityClaims(UUID.randomUUID(), "MANAGER", UUID.randomUUID());
        AuthTokenBundle tokens = new AuthTokenBundle("access", "refresh");
        when(external.findByProviderSubject("neutral", "subject"))
                .thenReturn(Optional.of(new LinkedAccountView(accountId, LinkedAccountStatus.ACTIVE)));
        when(claimsPort.findIdentityClaims(accountId)).thenReturn(Optional.of(claims));
        when(tokenService.issue(accountId, claims)).thenReturn(tokens);

        OAuthLoginResult result = new OAuthLoginService(new OAuthProviderRegistry(List.of(provider)), stateStore,
                external, claimsPort, tokenService)
                .completeLogin(new OAuthCallbackCommand("neutral", "code", "state"));

        assertThat(result).isEqualTo(new OAuthLoginResult.ExistingAccountAuthenticated(accountId, tokens));
        verify(tokenService).issue(accountId, claims);
    }

    @Test
    void providerAuthenticationFailureIsMappedButInfrastructureFailuresPropagate() {
        CapturingStateStore stateStore = new CapturingStateStore();
        stateStore.record = state();
        CapturingProvider provider = new CapturingProvider("neutral", false, true);
        provider.exchangeFailure = new OAuthProviderException(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        OAuthLoginService service = loginService(provider, stateStore);

        assertThat(service.completeLogin(new OAuthCallbackCommand("neutral", "code", "state")))
                .isEqualTo(new OAuthLoginResult.ProviderAuthenticationFailed(
                        OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED));
    }

    @Test
    void redirectResolverUsesClientSpecificConfigAndLegacyCompatibilityOnlyForCarelogDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("oauth.redirect-uris.neutral.WEB", REDIRECT.toString());
        OAuthRedirectUriResolver resolver = new OAuthRedirectUriResolver(environment);

        assertThat(resolver.resolve("NEUTRAL", new RegisteredProductClient(
                "carelog-web", Product.CARELOG, ProductClientChannel.WEB))).isEqualTo(REDIRECT);
        assertThatThrownBy(() -> resolver.resolve("neutral", new RegisteredProductClient(
                "finance-web", Product.CARELOG, ProductClientChannel.WEB)))
                .isInstanceOf(OAuthRequestException.class)
                .satisfies(error -> assertThat(((OAuthRequestException) error).reason())
                        .isEqualTo(OAuthRequestException.Reason.UNSUPPORTED_CLIENT_CHANNEL));
    }

    @Test
    void returnToValidatorRejectsOpenRedirectAndKeepsSafeRelativeInputVerbatim() {
        ReturnToValidator validator = new ReturnToValidator(List.of("https://app.example.test"));

        assertThat(validator.validate("/home?tab=recent")).isEqualTo("/home?tab=recent");
        assertThat(validator.validate("https://app.example.test/onboarding"))
                .isEqualTo("https://app.example.test/onboarding");
        assertThatThrownBy(() -> validator.validate("//evil.example.test"))
                .isInstanceOf(OAuthRequestException.class)
                .satisfies(error -> assertThat(((OAuthRequestException) error).reason())
                        .isEqualTo(OAuthRequestException.Reason.INVALID_OAUTH_RETURN_TO));
        assertThatThrownBy(() -> validator.validate("/\\evil.example.test"))
                .isInstanceOf(OAuthRequestException.class);
    }

    private OAuthAuthorizationService authorizationService(CapturingProvider provider,
                                                            CapturingStateStore stateStore,
                                                            MockEnvironment environment) {
        return new OAuthAuthorizationService(
                new OAuthProviderRegistry(List.of(provider)),
                new OAuthRedirectUriResolver(environment),
                new OAuthProductClientCompatibilityResolver(clientId ->
                        new RegisteredProductClient(clientId, Product.CARELOG,
                                clientId.endsWith("mobile") ? ProductClientChannel.MOBILE : ProductClientChannel.WEB)),
                new ReturnToValidator(List.of()), stateStore, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OAuthLoginService loginService(CapturingProvider provider, CapturingStateStore stateStore) {
        return new OAuthLoginService(new OAuthProviderRegistry(List.of(provider)), stateStore,
                (providerCode, providerSubject) -> Optional.empty(),
                mock(CRMIdentityProjectionPort.class), mock(AuthTokenIssuanceService.class));
    }

    private OAuthStateRecord state() {
        return new OAuthStateRecord("neutral", REDIRECT, "/home", "verifier", null, NOW);
    }

    private static final class CapturingStateStore implements OAuthStateStore {
        private OAuthStateRecord record;
        private Duration ttl;

        @Override
        public void save(String state, OAuthStateRecord record, Duration ttl) {
            this.record = record;
            this.ttl = ttl;
        }

        @Override
        public Optional<OAuthStateRecord> consume(String state) {
            OAuthStateRecord consumed = record;
            record = null;
            return Optional.ofNullable(consumed);
        }
    }

    private static final class CapturingProvider implements OAuthProviderPort {
        private final String code;
        private final boolean nonce;
        private final boolean pkce;
        private OAuthAuthorizationRequest request;
        private OAuthPrincipal principal;
        private OAuthProviderException exchangeFailure;
        private boolean exchangeCalled;

        private CapturingProvider(String code, boolean nonce, boolean pkce) {
            this.code = code;
            this.nonce = nonce;
            this.pkce = pkce;
        }

        @Override
        public String providerCode() { return code; }

        @Override
        public boolean requiresNonce() { return nonce; }

        @Override
        public boolean supportsPkce() { return pkce; }

        @Override
        public URI buildAuthorizationUrl(OAuthAuthorizationRequest request) {
            this.request = request;
            return URI.create("https://provider.example/authorize");
        }

        @Override
        public OAuthTokenGrant exchangeCode(String authorizationCode, URI redirectUri, String codeVerifier) {
            exchangeCalled = true;
            if (exchangeFailure != null) throw exchangeFailure;
            return new OAuthTokenGrant("token", null, NOW.plusSeconds(300));
        }

        @Override
        public OAuthPrincipal fetchPrincipal(OAuthTokenGrant grant, OAuthStateRecord state) {
            return principal;
        }
    }
}
