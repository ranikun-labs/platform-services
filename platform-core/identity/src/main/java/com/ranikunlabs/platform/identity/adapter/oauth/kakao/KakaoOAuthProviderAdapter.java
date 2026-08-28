package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto.KakaoTokenResponse;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto.KakaoUserResponse;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

/** Kakao-specific implementation of the provider-neutral OAuth port. */
@Conditional(KakaoOAuthConfiguredCondition.class)
public class KakaoOAuthProviderAdapter implements OAuthProviderPort {

    private final KakaoOAuthApiClient apiClient;
    private final KakaoOAuthProperties properties;
    private final Clock clock;

    public KakaoOAuthProviderAdapter(KakaoOAuthApiClient apiClient,
                                     KakaoOAuthProperties properties,
                                     Clock clock) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String providerCode() {
        return "kakao";
    }

    @Override
    public URI buildAuthorizationUrl(OAuthAuthorizationRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.getAuthorizationUri())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", request.redirectUri().toString())
                .queryParam("response_type", "code")
                .queryParam("state", request.state());
        if (request.codeChallenge() != null) {
            builder.queryParam("code_challenge", request.codeChallenge())
                    .queryParam("code_challenge_method", request.codeChallengeMethod());
        }
        return builder.build().encode().toUri();
    }

    @Override
    public OAuthTokenGrant exchangeCode(String authorizationCode, URI redirectUri, String codeVerifier) {
        KakaoTokenResponse response = apiClient.exchangeCode(authorizationCode, redirectUri, codeVerifier);
        long expiresIn = response.expiresIn() == null ? 0L : response.expiresIn();
        return new OAuthTokenGrant(
                response.accessToken(),
                null,
                Instant.now(clock).plusSeconds(Math.max(expiresIn, 0L))
        );
    }

    @Override
    public OAuthPrincipal fetchPrincipal(OAuthTokenGrant grant, OAuthStateRecord state) {
        KakaoUserResponse response = apiClient.fetchUser(grant.accessToken());
        if (response == null || response.id() == null || response.id() <= 0) {
            throw new OAuthProviderException(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        }
        return new OAuthPrincipal(providerCode(), Long.toString(response.id()), null, false, null);
    }
}
