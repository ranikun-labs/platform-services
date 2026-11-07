package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccessTokenPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Credentials-to-access-only use case for selected nonproduction Finance accounts. */
public final class FinanceReferenceAccessService {

    private final CredentialPort credentials;
    private final Set<UUID> selectedAccountIds;
    private final FinanceReferenceAccessTokenPort tokenIssuer;

    public FinanceReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> selectedAccountIds,
            FinanceReferenceAccessTokenPort tokenIssuer
    ) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.selectedAccountIds = Set.copyOf(selectedAccountIds);
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        if (this.selectedAccountIds.isEmpty() || this.selectedAccountIds.size() > 16) {
            throw new IllegalArgumentException("Finance reference requires 1–16 selected synthetic accounts.");
        }
    }

    public IssuedFinanceReferenceAccessToken authenticate(String userId, String password)
            throws InvalidCredentialsException {
        AuthenticatedIdentity identity = credentials.authenticate(userId, password);
        if (identity == null || identity.accountId() == null || !selectedAccountIds.contains(identity.accountId())) {
            throw new InvalidCredentialsException();
        }
        return tokenIssuer.issue(identity.accountId().toString());
    }
}
