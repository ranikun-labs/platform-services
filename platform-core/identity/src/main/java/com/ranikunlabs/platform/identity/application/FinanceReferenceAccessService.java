package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccessTokenPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceEnrollmentPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Credentials-to-access-only use case for selected nonproduction Finance accounts. */
public final class FinanceReferenceAccessService {

    private final CredentialPort credentials;
    private final Set<UUID> selectedAccountIds;
    private final FinanceReferenceEnrollmentPort enrollment;
    private final FinanceReferenceAccessTokenPort tokenIssuer;

    /** Admits only the selected synthetic accounts. */
    public FinanceReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> selectedAccountIds,
            FinanceReferenceAccessTokenPort tokenIssuer
    ) {
        this(credentials, selectedAccountIds, accountId -> false, tokenIssuer);
    }

    /**
     * Admits the selected synthetic accounts and any account enrolled through the Finance signup.
     * The selected allowlist stays mandatory (1–16 entries) so the fixture composition cannot start open.
     */
    public FinanceReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> selectedAccountIds,
            FinanceReferenceEnrollmentPort enrollment,
            FinanceReferenceAccessTokenPort tokenIssuer
    ) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.selectedAccountIds = Set.copyOf(selectedAccountIds);
        this.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        if (this.selectedAccountIds.isEmpty() || this.selectedAccountIds.size() > 16) {
            throw new IllegalArgumentException("Finance reference requires 1–16 selected synthetic accounts.");
        }
    }

    public IssuedFinanceReferenceAccessToken authenticate(String userId, String password)
            throws InvalidCredentialsException {
        AuthenticatedIdentity identity = credentials.authenticate(userId, password);
        if (identity == null || identity.accountId() == null || !isAdmitted(identity.accountId())) {
            throw new InvalidCredentialsException();
        }
        return tokenIssuer.issue(identity.accountId().toString());
    }

    private boolean isAdmitted(UUID accountId) {
        return selectedAccountIds.contains(accountId) || enrollment.isFinanceEnrolled(accountId);
    }
}
