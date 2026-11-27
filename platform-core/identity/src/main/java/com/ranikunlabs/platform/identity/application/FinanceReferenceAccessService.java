package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccessTokenPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceEnrollmentPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Credentials-to-access-only use case for selected nonproduction Finance accounts. */
public final class FinanceReferenceAccessService {

    private final CredentialPort credentials;
    private final Set<UUID> selectedAccountIds;
    private final FinanceReferenceEnrollmentPort enrollment;
    private final FinanceReferenceAccessTokenPort tokenIssuer;

    /** Admits only the selected synthetic accounts (1–16). */
    public FinanceReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> selectedAccountIds,
            FinanceReferenceAccessTokenPort tokenIssuer
    ) {
        this(credentials, selectedAccountIds, accountId -> false, tokenIssuer, false);
    }

    /**
     * Admits the selected synthetic accounts and any account enrolled through the Finance signup.
     * The selected set may be empty only when {@code zeroSelectedAllowed} is true (computed in configuration
     * from the same rule as the configuration guard: DEV dual-auth opt-in). Otherwise it
     * must hold 1–16 entries. More than 16 is always rejected.
     */
    public FinanceReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> selectedAccountIds,
            FinanceReferenceEnrollmentPort enrollment,
            FinanceReferenceAccessTokenPort tokenIssuer,
            boolean zeroSelectedAllowed
    ) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.selectedAccountIds = Set.copyOf(selectedAccountIds);
        this.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        int size = this.selectedAccountIds.size();
        if (size > 16 || (size == 0 && !zeroSelectedAllowed)) {
            throw new IllegalArgumentException("Finance reference requires 1–16 selected synthetic accounts.");
        }
    }

    public IssuedFinanceReferenceAccessToken authenticate(String userId, String password)
            throws InvalidCredentialsException {
        AuthenticatedIdentity identity = credentials.authenticate(normalizeLoginId(userId), password);
        if (identity == null || identity.accountId() == null || !isAdmitted(identity.accountId())) {
            throw new InvalidCredentialsException();
        }
        return tokenIssuer.issue(identity.accountId().toString());
    }

    /**
     * Same trim+lowercase rule the signup path stores, so login matches the stored normalized loginId.
     * Applied only on the Finance reference path; the shared Carelog login keeps its exact-match lookup.
     */
    static String normalizeLoginId(String userId) throws InvalidCredentialsException {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        return userId.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAdmitted(UUID accountId) {
        return selectedAccountIds.contains(accountId) || enrollment.isFinanceEnrolled(accountId);
    }
}
