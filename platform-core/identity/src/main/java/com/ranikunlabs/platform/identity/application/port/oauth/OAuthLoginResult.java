package com.ranikunlabs.platform.identity.application.port.oauth;

import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;

import java.util.UUID;

/** Exhaustive provider-neutral result of completing an OAuth login. */
public sealed interface OAuthLoginResult {

    record ExistingAccountAuthenticated(UUID accountId, AuthTokenBundle tokens) implements OAuthLoginResult {
    }

    record NewAccountOnboardingRequired(OnboardingCandidate candidate) implements OAuthLoginResult {
    }

    record ExternalIdentityConflict(ConflictReason reason) implements OAuthLoginResult {
    }

    record ProviderAuthenticationFailed(FailureReason reason) implements OAuthLoginResult {
    }

    record InvalidOrExpiredState() implements OAuthLoginResult {
    }

    enum ConflictReason {
        ACCOUNT_INACTIVE,
        ORPHANED_IDENTITY
    }

    enum FailureReason {
        CODE_EXCHANGE_FAILED,
        PRINCIPAL_UNVERIFIED,
        PROVIDER_UNAVAILABLE
    }
}
