package com.ranikunlabs.platform.identity.application.port.oauth;

import java.net.URI;

/** Provider-neutral port isolating OAuth SDK and HTTP adapters from the OAuth core. */
public interface OAuthProviderPort {

    String providerCode();

    default boolean supportsPkce() {
        return true;
    }

    /** Whether this provider requires a nonce for ID-token verification. */
    default boolean requiresNonce() {
        return false;
    }

    URI buildAuthorizationUrl(OAuthAuthorizationRequest request);

    OAuthTokenGrant exchangeCode(String authorizationCode, URI redirectUri, String codeVerifier);

    OAuthPrincipal fetchPrincipal(OAuthTokenGrant grant, OAuthStateRecord state);
}
