package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

/**
 * Kakao REST Login behind the provider-neutral OAuth port.
 *
 * <p>This is the ordinary (non-OIDC) Kakao Login flow. The authorization request carries exactly the
 * documented parameters — {@code client_id}, {@code redirect_uri}, {@code response_type}, and the
 * Platform-generated {@code state} — and nothing else: no scope, prompt, login_hint, or nonce, none
 * of which the current Identity flow needs.
 */
public class KakaoOAuthProviderAdapter implements OAuthProviderPort {

    private static final String PROVIDER_CODE = "kakao";

    private final KakaoOAuthApiClient apiClient;
    private final KakaoOAuthProperties properties;
    private final Clock clock;

    KakaoOAuthProviderAdapter(KakaoOAuthApiClient apiClient, KakaoOAuthProperties properties, Clock clock) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    /**
     * The documented ordinary Kakao REST Login authorization and token requests do not define
     * {@code code_challenge} / {@code code_verifier}, so this adapter opts out of the port's default
     * rather than sending parameters the provider's published contract does not specify. Turning
     * PKCE on belongs with a documented Kakao OIDC flow, which this slice does not implement.
     */
    @Override
    public boolean supportsPkce() {
        return false;
    }

    /** Non-OIDC: there is no ID token to bind, so a nonce would carry no verification value. */
    @Override
    public boolean requiresNonce() {
        return false;
    }

    @Override
    public URI buildAuthorizationUrl(OAuthAuthorizationRequest request) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", request.redirectUri().toString())
                .queryParam("response_type", "code")
                .queryParam("state", request.state())
                .build()
                .encode()
                .toUri();
    }

    /**
     * The redirect URI is the server-side value recorded when authorization started, so Kakao sees
     * the same value it was given and the browser callback stays owned by the product client.
     */
    @Override
    public OAuthTokenGrant exchangeCode(String authorizationCode, URI redirectUri, String codeVerifier) {
        KakaoTokenResponse response = apiClient.exchangeCode(authorizationCode, redirectUri);
        long expiresInSeconds = response.expiresIn() == null ? 0L : Math.max(response.expiresIn(), 0L);
        return new OAuthTokenGrant(
                response.accessToken(), null, Instant.now(clock).plusSeconds(expiresInSeconds));
    }

    /**
     * The Kakao service user id is the only identity key. Email, nickname, and profile are neither
     * requested nor consumed, so an account is never linked by a mutable or unverified attribute.
     */
    @Override
    public OAuthPrincipal fetchPrincipal(OAuthTokenGrant grant, OAuthStateRecord state) {
        KakaoUserResponse response = apiClient.fetchUser(grant.accessToken());
        if (response == null || response.id() == null || response.id() <= 0) {
            throw KakaoOAuthErrorMapper.principalUnverified();
        }
        return new OAuthPrincipal(PROVIDER_CODE, Long.toString(response.id()), null, false, null);
    }
}
