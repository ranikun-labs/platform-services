package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.FinanceReferenceAccessService;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccessTokenPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinanceReferenceAccessServiceTest {

    private static final UUID SELECTED = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID UNSELECTED = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void selectedAuthenticatedAccountGetsOnlyOpaqueSubjectAccessToken() {
        CredentialPort credentials = mock(CredentialPort.class);
        FinanceReferenceAccessTokenPort issuer = mock(FinanceReferenceAccessTokenPort.class);
        FinanceReferenceAccessService service = new FinanceReferenceAccessService(
                credentials, Set.of(SELECTED), issuer);
        when(credentials.authenticate("reference-user", "test-password"))
                .thenReturn(new AuthenticatedIdentity(SELECTED, "reference-user"));
        IssuedFinanceReferenceAccessToken token = new IssuedFinanceReferenceAccessToken(
                "public-dummy-token", Instant.parse("2026-11-06T13:00:00Z"));
        when(issuer.issue(SELECTED.toString())).thenReturn(token);

        assertThat(service.authenticate("reference-user", "test-password")).isEqualTo(token);

        verify(issuer).issue(SELECTED.toString());
    }

    @Test
    void validCredentialForAccountOutsideSelectedAllowlistIsRejectedBeforeIssuance() {
        CredentialPort credentials = mock(CredentialPort.class);
        FinanceReferenceAccessTokenPort issuer = mock(FinanceReferenceAccessTokenPort.class);
        FinanceReferenceAccessService service = new FinanceReferenceAccessService(
                credentials, Set.of(SELECTED), issuer);
        when(credentials.authenticate("other-user", "test-password"))
                .thenReturn(new AuthenticatedIdentity(UNSELECTED, "other-user"));

        assertThatThrownBy(() -> service.authenticate("other-user", "test-password"))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(issuer);
    }

    @Test
    void selectedAccountSetMustBeBoundedAndNonempty() {
        CredentialPort credentials = mock(CredentialPort.class);
        FinanceReferenceAccessTokenPort issuer = mock(FinanceReferenceAccessTokenPort.class);

        assertThatThrownBy(() -> new FinanceReferenceAccessService(credentials, Set.of(), issuer))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinanceReferenceAccessService(
                credentials, Set.of(SELECTED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()), issuer))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
