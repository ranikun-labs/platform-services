package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountStatus;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountView;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final OAuthStateRecord STATE_RECORD = new OAuthStateRecord(
            "kakao", URI.create("https://carelog.example/callback"), "/dashboard", "verifier", null, Instant.now());
    private static final OAuthCallbackCommand COMMAND = new OAuthCallbackCommand("kakao", "auth-code", "state-value");

    @Mock
    private OAuthProviderRegistry providerRegistry;
    @Mock
    private OAuthStateStore stateStore;
    @Mock
    private ExternalIdentityLookupPort externalIdentityLookupPort;
    @Mock
    private CRMIdentityProjectionPort crmIdentityProjectionPort;
    @Mock
    private AuthTokenIssuanceService authTokenIssuanceService;
    @Mock
    private OAuthProviderPort provider;

    private OAuthLoginService service;

    @BeforeEach
    void setUp() {
        service = new OAuthLoginService(
                providerRegistry, stateStore, externalIdentityLookupPort, crmIdentityProjectionPort,
                authTokenIssuanceService);
        when(providerRegistry.resolve("kakao")).thenReturn(provider);
        when(provider.providerCode()).thenReturn("kakao");
    }

    @Test
    void missingStateReturnsInvalidOrExpiredStateWithoutCallingProvider() {
        when(stateStore.consume("state-value")).thenReturn(Optional.empty());

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isInstanceOf(OAuthLoginResult.InvalidOrExpiredState.class);
        verify(provider, never()).exchangeCode(any(), any(), any());
    }

    @Test
    void reusedStateFailsClosedOnSecondConsumeJustLikeAMissingState() {
        when(stateStore.consume("state-value"))
                .thenReturn(Optional.of(STATE_RECORD))
                .thenReturn(Optional.empty());
        // Simulate the store's own one-time GETDEL semantics by draining it here first.
        stateStore.consume("state-value");

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isInstanceOf(OAuthLoginResult.InvalidOrExpiredState.class);
    }

    @Test
    void providerMismatchBetweenStateAndCallbackFailsClosed() {
        OAuthStateRecord mismatchedRecord = new OAuthStateRecord(
                "google", URI.create("https://carelog.example/callback"), "/dashboard", "verifier", null, Instant.now());
        when(stateStore.consume("state-value")).thenReturn(Optional.of(mismatchedRecord));

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isInstanceOf(OAuthLoginResult.InvalidOrExpiredState.class);
        verify(provider, never()).exchangeCode(any(), any(), any());
    }

    @Test
    void providerExchangeFailurePropagatesAsTypedResult() {
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier()))
                .thenThrow(new OAuthProviderException(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED));

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isEqualTo(
                new OAuthLoginResult.ProviderAuthenticationFailed(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED));
    }

    @Test
    void blankPrincipalSubjectIsTreatedAsUnverified() {
        OAuthTokenGrant grant = new OAuthTokenGrant("access", null, Instant.now().plusSeconds(3600));
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier())).thenReturn(grant);
        when(provider.fetchPrincipal(grant, STATE_RECORD))
                .thenReturn(new OAuthPrincipal("kakao", "  ", "user@example.com", true, "User"));

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isEqualTo(
                new OAuthLoginResult.ProviderAuthenticationFailed(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED));
    }

    @Test
    void newAccountReturnsOnboardingCandidateWithReturnToFromState() {
        OAuthTokenGrant grant = new OAuthTokenGrant("access", null, Instant.now().plusSeconds(3600));
        OAuthPrincipal principal = new OAuthPrincipal("kakao", "provider-subject", "user@example.com", true, "User");
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier())).thenReturn(grant);
        when(provider.fetchPrincipal(grant, STATE_RECORD)).thenReturn(principal);
        when(externalIdentityLookupPort.findByProviderSubject("kakao", "provider-subject")).thenReturn(Optional.empty());

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isInstanceOf(OAuthLoginResult.NewAccountOnboardingRequired.class);
        var candidate = ((OAuthLoginResult.NewAccountOnboardingRequired) result).candidate();
        assertThat(candidate.provider()).isEqualTo("kakao");
        assertThat(candidate.providerSubject()).isEqualTo("provider-subject");
        assertThat(candidate.returnTo()).isEqualTo("/dashboard");
    }

    @Test
    void inactiveLinkedAccountReturnsExternalIdentityConflict() {
        OAuthTokenGrant grant = new OAuthTokenGrant("access", null, Instant.now().plusSeconds(3600));
        OAuthPrincipal principal = new OAuthPrincipal("kakao", "provider-subject", "user@example.com", true, "User");
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier())).thenReturn(grant);
        when(provider.fetchPrincipal(grant, STATE_RECORD)).thenReturn(principal);
        when(externalIdentityLookupPort.findByProviderSubject("kakao", "provider-subject"))
                .thenReturn(Optional.of(new LinkedAccountView(ACCOUNT_ID, LinkedAccountStatus.INACTIVE)));

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isEqualTo(
                new OAuthLoginResult.ExternalIdentityConflict(OAuthLoginResult.ConflictReason.ACCOUNT_INACTIVE));
    }

    @Test
    void orphanedIdentityReturnsExternalIdentityConflictWhenClaimsAreMissing() {
        OAuthTokenGrant grant = new OAuthTokenGrant("access", null, Instant.now().plusSeconds(3600));
        OAuthPrincipal principal = new OAuthPrincipal("kakao", "provider-subject", "user@example.com", true, "User");
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier())).thenReturn(grant);
        when(provider.fetchPrincipal(grant, STATE_RECORD)).thenReturn(principal);
        when(externalIdentityLookupPort.findByProviderSubject("kakao", "provider-subject"))
                .thenReturn(Optional.of(new LinkedAccountView(ACCOUNT_ID, LinkedAccountStatus.ACTIVE)));
        when(crmIdentityProjectionPort.findIdentityClaims(ACCOUNT_ID)).thenReturn(Optional.empty());

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isEqualTo(
                new OAuthLoginResult.ExternalIdentityConflict(OAuthLoginResult.ConflictReason.ORPHANED_IDENTITY));
    }

    @Test
    void existingActiveAccountIsAuthenticatedThroughTheExistingTokenPath() {
        OAuthTokenGrant grant = new OAuthTokenGrant("access", null, Instant.now().plusSeconds(3600));
        OAuthPrincipal principal = new OAuthPrincipal("kakao", "provider-subject", "user@example.com", true, "User");
        CRMIdentityClaims claims = new CRMIdentityClaims(UUID.randomUUID(), "MEMBER", UUID.randomUUID());
        AuthTokenBundle tokens = new AuthTokenBundle("access-token", "refresh-token");
        when(stateStore.consume("state-value")).thenReturn(Optional.of(STATE_RECORD));
        when(provider.exchangeCode("auth-code", STATE_RECORD.redirectUri(), STATE_RECORD.codeVerifier())).thenReturn(grant);
        when(provider.fetchPrincipal(grant, STATE_RECORD)).thenReturn(principal);
        when(externalIdentityLookupPort.findByProviderSubject("kakao", "provider-subject"))
                .thenReturn(Optional.of(new LinkedAccountView(ACCOUNT_ID, LinkedAccountStatus.ACTIVE)));
        when(crmIdentityProjectionPort.findIdentityClaims(ACCOUNT_ID)).thenReturn(Optional.of(claims));
        when(authTokenIssuanceService.issue(ACCOUNT_ID, claims)).thenReturn(tokens);

        OAuthLoginResult result = service.completeLogin(COMMAND);

        assertThat(result).isEqualTo(new OAuthLoginResult.ExistingAccountAuthenticated(ACCOUNT_ID, tokens));
    }
}
