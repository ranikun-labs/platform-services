package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.CRMIdentityClaimsPolicy;
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
import com.ranikunlabs.platform.identity.application.port.oauth.OnboardingCandidate;

import java.util.Optional;
import java.util.UUID;

/** Consumes one-time OAuth state, verifies the provider principal, and follows the Identity login seam. */
public class OAuthLoginService {

    private final OAuthProviderRegistry providerRegistry;
    private final OAuthStateStore stateStore;
    private final ExternalIdentityLookupPort externalIdentityLookupPort;
    private final CRMIdentityProjectionPort crmIdentityProjectionPort;
    private final AuthTokenIssuanceService authTokenIssuanceService;

    public OAuthLoginService(OAuthProviderRegistry providerRegistry,
                             OAuthStateStore stateStore,
                             ExternalIdentityLookupPort externalIdentityLookupPort,
                             CRMIdentityProjectionPort crmIdentityProjectionPort,
                             AuthTokenIssuanceService authTokenIssuanceService) {
        this.providerRegistry = providerRegistry;
        this.stateStore = stateStore;
        this.externalIdentityLookupPort = externalIdentityLookupPort;
        this.crmIdentityProjectionPort = crmIdentityProjectionPort;
        this.authTokenIssuanceService = authTokenIssuanceService;
    }

    public OAuthLoginResult completeLogin(OAuthCallbackCommand command) {
        OAuthProviderPort provider = providerRegistry.resolve(command.provider());
        String providerCode = OAuthProviderRegistry.normalize(provider.providerCode());
        Optional<OAuthStateRecord> state = stateStore.consume(command.state());
        if (state.isEmpty() || !providerCode.equals(OAuthProviderRegistry.normalize(state.get().provider()))) {
            return new OAuthLoginResult.InvalidOrExpiredState();
        }

        OAuthPrincipal principal;
        try {
            principal = provider.fetchPrincipal(
                    provider.exchangeCode(command.authorizationCode(), state.get().redirectUri(), state.get().codeVerifier()),
                    state.get()
            );
        } catch (OAuthProviderException exception) {
            return new OAuthLoginResult.ProviderAuthenticationFailed(exception.reason());
        }

        if (!providerCode.equals(OAuthProviderRegistry.normalize(principal.provider()))
                || principal.providerSubject() == null || principal.providerSubject().isBlank()) {
            return new OAuthLoginResult.ProviderAuthenticationFailed(
                    OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        }

        Optional<LinkedAccountView> linkedAccount = externalIdentityLookupPort
                .findByProviderSubject(providerCode, principal.providerSubject());
        if (linkedAccount.isEmpty()) {
            return new OAuthLoginResult.NewAccountOnboardingRequired(new OnboardingCandidate(
                    providerCode,
                    principal.providerSubject(),
                    principal.email(),
                    principal.emailVerified(),
                    principal.displayName(),
                    state.get().returnTo()
            ));
        }

        if (linkedAccount.get().status() != LinkedAccountStatus.ACTIVE) {
            return new OAuthLoginResult.ExternalIdentityConflict(
                    OAuthLoginResult.ConflictReason.ACCOUNT_INACTIVE);
        }

        return crmIdentityProjectionPort.findIdentityClaims(linkedAccount.get().accountId())
                .<OAuthLoginResult>map(claims -> new OAuthLoginResult.ExistingAccountAuthenticated(
                        linkedAccount.get().accountId(),
                        issueEligibleClaims(linkedAccount.get().accountId(), claims)
                ))
                .orElseGet(() -> new OAuthLoginResult.ExternalIdentityConflict(
                        OAuthLoginResult.ConflictReason.ORPHANED_IDENTITY));
    }

    private AuthTokenBundle issueEligibleClaims(
            UUID accountId,
            CRMIdentityClaims claims
    ) {
        CRMIdentityClaimsPolicy.requireEligible(claims);
        return authTokenIssuanceService.issue(accountId, claims);
    }
}
