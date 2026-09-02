package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PUBLIC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private CredentialPort credentialPort;
    @Mock
    private CRMIdentityProjectionPort claimsPort;
    @Mock
    private TokenSessionPort sessionPort;
    @Mock
    private TokenProviderPort tokenProvider;
    @Mock
    private TokenBlacklistPort blacklistPort;
    @Mock
    private AccountStatusPort accountStatusPort;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(
                credentialPort, claimsPort, sessionPort, tokenProvider, blacklistPort,
                accountStatusPort, CLOCK,
                new com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService(tokenProvider, sessionPort));
    }

    @Test
    void requiresAccountStatusPortForEveryConstructionPath() {
        Constructor<?>[] constructors = AuthServiceImpl.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).contains(AccountStatusPort.class);
    }

    @Test
    void loginAuthenticatesProjectsLatestClaimsThenIssuesSession() {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(ACCOUNT_ID, "manager@example.com");
        CRMIdentityClaims claims = new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        OffsetDateTime expiry = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        when(credentialPort.authenticate("manager@example.com", "raw-password")).thenReturn(identity);
        when(claimsPort.getIdentityClaims(ACCOUNT_ID)).thenReturn(claims);
        when(tokenProvider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID))
                .thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(ACCOUNT_ID)).thenReturn("refresh-token");
        when(tokenProvider.getRefreshTokenExpiryDate()).thenReturn(expiry);

        assertThat(service.login("manager@example.com", "raw-password"))
                .isEqualTo(new AuthTokenBundle("access-token", "refresh-token"));

        InOrder order = inOrder(credentialPort, claimsPort, tokenProvider, sessionPort);
        order.verify(credentialPort).authenticate("manager@example.com", "raw-password");
        order.verify(claimsPort).getIdentityClaims(ACCOUNT_ID);
        order.verify(tokenProvider).generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        order.verify(tokenProvider).generateRefreshToken(ACCOUNT_ID);
        order.verify(sessionPort).replaceForAccount(ACCOUNT_ID, "refresh-token", expiry);
    }

    @Test
    void loginDoesNotIssueTokensWhenProductClaimsDependencyFails() {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(ACCOUNT_ID, "manager@example.com");
        CRMIdentityProjectionException failure = new CRMIdentityProjectionException(
                CRMIdentityProjectionException.Reason.DEPENDENCY_UNAVAILABLE);
        when(credentialPort.authenticate("manager@example.com", "raw-password"))
                .thenReturn(identity);
        when(claimsPort.getIdentityClaims(ACCOUNT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.login("manager@example.com", "raw-password"))
                .isSameAs(failure);

        verifyNoInteractions(tokenProvider, sessionPort);
    }

    @Test
    void loginDoesNotIssueTokensForAnIneligibleCustomerProjection() {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(ACCOUNT_ID, "manager@example.com");
        when(credentialPort.authenticate("manager@example.com", "raw-password"))
                .thenReturn(identity);
        when(claimsPort.getIdentityClaims(ACCOUNT_ID))
                .thenReturn(new CRMIdentityClaims(ORGANIZATION_ID, "CUSTOMER", PUBLIC_ID));

        assertThatThrownBy(() -> service.login("manager@example.com", "raw-password"))
                .isInstanceOfSatisfying(CRMIdentityProjectionException.class, error ->
                        assertThat(error.reason())
                                .isEqualTo(CRMIdentityProjectionException.Reason.PRODUCT_ROLE_NOT_ELIGIBLE));

        verifyNoInteractions(tokenProvider, sessionPort);
    }

    @Test
    void refreshRejectsInvalidJwtBeforeSessionLookup() {
        when(tokenProvider.validateToken("bad-refresh")).thenReturn(false);

        assertThatThrownBy(() -> service.refreshToken("bad-refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(sessionPort, claimsPort, accountStatusPort);
    }

    @Test
    void refreshRejectsLegacyNonUuidSubjectBeforeSessionLookup() {
        when(tokenProvider.validateToken("legacy-refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("legacy-refresh"))
                .thenThrow(new IllegalArgumentException("legacy loginId subject"));

        assertThatThrownBy(() -> service.refreshToken("legacy-refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(sessionPort, claimsPort, accountStatusPort);
    }

    @Test
    void refreshRejectsMissingPersistedSessionBeforeClaimsOrTokenIssuance() {
        when(tokenProvider.validateToken("missing-refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("missing-refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("missing-refresh")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshToken("missing-refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND));
        verifyNoInteractions(claimsPort, accountStatusPort);
        verify(tokenProvider, never()).generateAccessToken(any(), any(), any(), any());
        verify(tokenProvider, never()).generateRefreshToken(any());
    }

    @Test
    void refreshDeletesExpiredSessionBeforeReportingExpiry() {
        RefreshSession session = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).minusSeconds(1));
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.REFRESH_TOKEN_EXPIRED));
        verify(sessionPort).delete(session);
        verifyNoInteractions(claimsPort, accountStatusPort);
    }

    @Test
    void refreshRequiresMatchingActiveAccountAndRotatesExactSession() {
        RefreshSession session = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        CRMIdentityClaims claims = new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        OffsetDateTime expiry = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(session));
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(claimsPort.getIdentityClaims(ACCOUNT_ID)).thenReturn(claims);
        when(tokenProvider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID))
                .thenReturn("new-access");
        when(tokenProvider.generateRefreshToken(ACCOUNT_ID)).thenReturn("new-refresh");
        when(tokenProvider.getRefreshTokenExpiryDate()).thenReturn(expiry);

        assertThat(service.refreshToken("refresh"))
                .isEqualTo(new AuthTokenBundle("new-access", "new-refresh"));
        InOrder order = inOrder(tokenProvider, sessionPort, accountStatusPort, claimsPort);
        order.verify(tokenProvider).validateToken("refresh");
        order.verify(tokenProvider).getAccountIdFromToken("refresh");
        order.verify(sessionPort).findByToken("refresh");
        order.verify(accountStatusPort).findStatus(ACCOUNT_ID);
        order.verify(claimsPort).getIdentityClaims(ACCOUNT_ID);
        order.verify(tokenProvider).generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        order.verify(tokenProvider).generateRefreshToken(ACCOUNT_ID);
        order.verify(tokenProvider).getRefreshTokenExpiryDate();
        order.verify(sessionPort).rotate(any(RefreshSession.class), org.mockito.ArgumentMatchers.eq("new-refresh"),
                org.mockito.ArgumentMatchers.eq(expiry));
        ArgumentCaptor<RefreshSession> sessionCaptor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessionPort).rotate(sessionCaptor.capture(), org.mockito.ArgumentMatchers.eq("new-refresh"),
                org.mockito.ArgumentMatchers.eq(expiry));
        assertThat(sessionCaptor.getValue()).isSameAs(session);
    }

    @Test
    void refreshDoesNotRotateWhenProductClaimsDependencyFails() {
        RefreshSession session = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        CRMIdentityProjectionException failure = new CRMIdentityProjectionException(
                CRMIdentityProjectionException.Reason.DEPENDENCY_CONTRACT_FAILURE);
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(session));
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(claimsPort.getIdentityClaims(ACCOUNT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isSameAs(failure);

        verify(tokenProvider, never()).generateAccessToken(any(), any(), any(), any());
        verify(tokenProvider, never()).generateRefreshToken(any());
        verify(sessionPort, never()).rotate(any(), any(), any());
    }

    @Test
    void refreshDoesNotRotateForAnIneligibleCustomerProjection() {
        RefreshSession session = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(session));
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(claimsPort.getIdentityClaims(ACCOUNT_ID))
                .thenReturn(new CRMIdentityClaims(ORGANIZATION_ID, "CUSTOMER", PUBLIC_ID));

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOfSatisfying(CRMIdentityProjectionException.class, error ->
                        assertThat(error.reason())
                                .isEqualTo(CRMIdentityProjectionException.Reason.PRODUCT_ROLE_NOT_ELIGIBLE));

        verify(tokenProvider, never()).generateAccessToken(any(), any(), any(), any());
        verify(tokenProvider, never()).generateRefreshToken(any());
        verify(sessionPort, never()).rotate(any(), any(), any());
    }

    @Test
    void refreshRejectsMissingAccountBeforeClaimsOrTokenIssuance() {
        RefreshSession session = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(session));
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));
        verify(accountStatusPort).findStatus(ACCOUNT_ID);
        verifyNoInteractions(claimsPort);
        verify(tokenProvider, never()).generateAccessToken(any(), any(), any(), any());
        verify(tokenProvider, never()).generateRefreshToken(any());
        verify(sessionPort, never()).rotate(any(), any(), any());
    }

    @Test
    void refreshRejectsSubjectMismatchOrInactiveAccountWithoutClaims() {
        RefreshSession mismatch = new RefreshSession(
                "refresh", OTHER_ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("refresh")).thenReturn(ACCOUNT_ID);
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(mismatch));

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(accountStatusPort, claimsPort);

        RefreshSession inactive = new RefreshSession(
                "refresh", ACCOUNT_ID, OffsetDateTime.now(CLOCK).plusDays(1));
        when(sessionPort.findByToken("refresh")).thenReturn(Optional.of(inactive));
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.INACTIVE));

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOfSatisfying(TokenLifecycleException.class, error ->
                        assertThat(error.reason()).isEqualTo(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(claimsPort);
    }

    @Test
    void logoutBlacklistsBeforeDeletingSessionAndPropagatesBlacklistFailure() {
        when(tokenProvider.getRemainingValidity("access")).thenReturn(Duration.ofMinutes(5));
        service.logout(ACCOUNT_ID, "access");
        InOrder order = inOrder(blacklistPort, sessionPort);
        order.verify(blacklistPort).addToBlacklist("access", Duration.ofMinutes(5));
        order.verify(sessionPort).deleteForAccount(ACCOUNT_ID);

        org.mockito.Mockito.clearInvocations(sessionPort);
        doThrow(new IllegalStateException("redis down"))
                .when(blacklistPort).addToBlacklist("access", Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.logout(ACCOUNT_ID, "access"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
        verifyNoInteractions(sessionPort);
    }
}
