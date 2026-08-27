package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Behavior-preserving Identity authentication, refresh, and logout orchestration. */
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final CredentialPort credentialPort;
    private final CRMIdentityProjectionPort crmIdentityProjectionPort;
    private final TokenSessionPort tokenSessionPort;
    private final TokenProviderPort tokenProvider;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final AccountStatusPort accountStatusPort;
    private final Clock clock;
    private final AuthTokenIssuanceService tokenIssuanceService;

    @Autowired
    public AuthServiceImpl(
            CredentialPort credentialPort,
            CRMIdentityProjectionPort crmIdentityProjectionPort,
            TokenSessionPort tokenSessionPort,
            TokenProviderPort tokenProvider,
            TokenBlacklistPort tokenBlacklistPort,
            AccountStatusPort accountStatusPort,
            Clock clock,
            AuthTokenIssuanceService tokenIssuanceService
    ) {
        this.credentialPort = credentialPort;
        this.crmIdentityProjectionPort = crmIdentityProjectionPort;
        this.tokenSessionPort = tokenSessionPort;
        this.tokenProvider = tokenProvider;
        this.tokenBlacklistPort = tokenBlacklistPort;
        this.accountStatusPort = accountStatusPort;
        this.clock = clock;
        this.tokenIssuanceService = tokenIssuanceService;
    }

    /** Convenience constructor for unit callers that do not need account-status persistence. */
    public AuthServiceImpl(
            CredentialPort credentialPort,
            CRMIdentityProjectionPort crmIdentityProjectionPort,
            TokenSessionPort tokenSessionPort,
            TokenProviderPort tokenProvider,
            TokenBlacklistPort tokenBlacklistPort,
            Clock clock
    ) {
        this(
                credentialPort,
                crmIdentityProjectionPort,
                tokenSessionPort,
                tokenProvider,
                tokenBlacklistPort,
                accountId -> Optional.of(AccountStatus.ACTIVE),
                clock,
                new AuthTokenIssuanceService(tokenProvider, tokenSessionPort)
        );
    }

    @Override
    @Transactional
    public AuthTokenBundle login(String loginId, String password) {
        AuthenticatedIdentity identity = credentialPort.authenticate(loginId, password);
        CRMIdentityClaims claims = crmIdentityProjectionPort.getIdentityClaims(identity.accountId());
        return tokenIssuanceService.issue(identity.accountId(), claims);
    }

    @Override
    @Transactional
    public AuthTokenBundle refreshToken(String rawRefreshToken) {
        if (!tokenProvider.validateToken(rawRefreshToken)) {
            throw invalidRefreshToken();
        }

        UUID accountId;
        try {
            accountId = tokenProvider.getAccountIdFromToken(rawRefreshToken);
        } catch (IllegalArgumentException exception) {
            throw invalidRefreshToken();
        }

        RefreshSession session = tokenSessionPort.findByToken(rawRefreshToken)
                .orElseThrow(() -> new TokenLifecycleException(
                        TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND));

        if (OffsetDateTime.now(clock).isAfter(session.expiresAt())) {
            tokenSessionPort.delete(session);
            throw new TokenLifecycleException(TokenLifecycleException.Reason.REFRESH_TOKEN_EXPIRED);
        }

        if (!session.accountId().equals(accountId) || !isActive(accountId)) {
            throw invalidRefreshToken();
        }

        CRMIdentityClaims claims = crmIdentityProjectionPort.getIdentityClaims(accountId);
        return tokenIssuanceService.rotate(session, claims);
    }

    @Override
    @Transactional
    public void logout(UUID accountId, String accessToken) {
        var ttl = tokenProvider.getRemainingValidity(accessToken);
        tokenBlacklistPort.addToBlacklist(accessToken, ttl);
        // Preserve the source ordering: blacklist write must succeed before session deletion.
        tokenSessionPort.deleteForAccount(accountId);
    }

    private boolean isActive(UUID accountId) {
        return accountStatusPort.findStatus(accountId)
                .map(AccountStatus.ACTIVE::equals)
                .orElse(false);
    }

    private TokenLifecycleException invalidRefreshToken() {
        return new TokenLifecycleException(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN);
    }
}
