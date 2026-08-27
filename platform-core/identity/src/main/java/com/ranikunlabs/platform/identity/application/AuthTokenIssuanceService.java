package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Issues JWT pairs and coordinates their Identity-owned refresh-session storage. */
@Transactional
public class AuthTokenIssuanceService {

    private final TokenProviderPort tokenProvider;
    private final TokenSessionPort tokenSessionPort;

    public AuthTokenIssuanceService(TokenProviderPort tokenProvider, TokenSessionPort tokenSessionPort) {
        this.tokenProvider = tokenProvider;
        this.tokenSessionPort = tokenSessionPort;
    }

    /** Issues a login pair and replaces all existing sessions for the account. */
    public AuthTokenBundle issue(UUID accountId, CRMIdentityClaims claims) {
        String accessToken = tokenProvider.generateAccessToken(
                accountId,
                claims.organizationId(),
                claims.role(),
                claims.publicId()
        );
        String refreshToken = tokenProvider.generateRefreshToken(accountId);

        tokenSessionPort.replaceForAccount(
                accountId,
                refreshToken,
                tokenProvider.getRefreshTokenExpiryDate()
        );

        return new AuthTokenBundle(accessToken, refreshToken);
    }

    /** Issues a refresh pair and mutates the exact session returned by the lookup. */
    public AuthTokenBundle rotate(RefreshSession session, CRMIdentityClaims claims) {
        UUID accountId = session.accountId();
        String accessToken = tokenProvider.generateAccessToken(
                accountId,
                claims.organizationId(),
                claims.role(),
                claims.publicId()
        );
        String refreshToken = tokenProvider.generateRefreshToken(accountId);
        tokenSessionPort.rotate(session, refreshToken, tokenProvider.getRefreshTokenExpiryDate());
        return new AuthTokenBundle(accessToken, refreshToken);
    }
}
