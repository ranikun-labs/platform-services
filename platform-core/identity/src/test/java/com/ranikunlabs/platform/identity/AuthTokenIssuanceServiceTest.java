package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenIssuanceServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PUBLIC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private TokenProviderPort tokenProvider;
    @Mock
    private TokenSessionPort tokenSessionPort;

    @Test
    void issueGeneratesTokensThenReplacesAccountSession() {
        AuthTokenIssuanceService service = new AuthTokenIssuanceService(tokenProvider, tokenSessionPort);
        CRMIdentityClaims claims = new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        OffsetDateTime expiry = OffsetDateTime.parse("2026-08-10T00:00:00Z");
        when(tokenProvider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID))
                .thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(ACCOUNT_ID)).thenReturn("refresh-token");
        when(tokenProvider.getRefreshTokenExpiryDate()).thenReturn(expiry);

        assertThat(service.issue(ACCOUNT_ID, claims))
                .isEqualTo(new AuthTokenBundle("access-token", "refresh-token"));

        InOrder order = inOrder(tokenProvider, tokenSessionPort);
        order.verify(tokenProvider).generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        order.verify(tokenProvider).generateRefreshToken(ACCOUNT_ID);
        order.verify(tokenProvider).getRefreshTokenExpiryDate();
        order.verify(tokenSessionPort).replaceForAccount(ACCOUNT_ID, "refresh-token", expiry);
    }

    @Test
    void rotateUsesTheLookedUpSessionAndDoesNotReplaceOtherSessions() {
        AuthTokenIssuanceService service = new AuthTokenIssuanceService(tokenProvider, tokenSessionPort);
        CRMIdentityClaims claims = new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        RefreshSession session = new RefreshSession(
                "old-refresh", ACCOUNT_ID, OffsetDateTime.parse("2026-08-10T00:00:00Z"));
        OffsetDateTime expiry = OffsetDateTime.parse("2026-08-24T00:00:00Z");
        when(tokenProvider.generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID))
                .thenReturn("new-access");
        when(tokenProvider.generateRefreshToken(ACCOUNT_ID)).thenReturn("new-refresh");
        when(tokenProvider.getRefreshTokenExpiryDate()).thenReturn(expiry);

        assertThat(service.rotate(session, claims))
                .isEqualTo(new AuthTokenBundle("new-access", "new-refresh"));
        InOrder order = inOrder(tokenProvider, tokenSessionPort);
        order.verify(tokenProvider).generateAccessToken(ACCOUNT_ID, ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
        order.verify(tokenProvider).generateRefreshToken(ACCOUNT_ID);
        order.verify(tokenProvider).getRefreshTokenExpiryDate();
        order.verify(tokenSessionPort).rotate(session, "new-refresh", expiry);
    }
}
