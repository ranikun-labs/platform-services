package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.URI;

/**
 * Encapsulates the Kakao token and user-info round trips.
 *
 * <p>Nothing here logs or re-throws a credential: the authorization code, the client secret, the
 * Kakao access token, and every Kakao response body stay inside this class. Failures leave it only
 * as provider-neutral reasons.
 */
class KakaoOAuthApiClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthApiClient.class);

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;

    KakaoOAuthApiClient(RestClient restClient, KakaoOAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Exchanges a one-time authorization code.
     *
     * <p>Never retried: the code is single-use, so replaying it after an ambiguous outcome can
     * consume a credential Kakao already honoured and turn a success into a confusing failure.
     */
    KakaoTokenResponse exchangeCode(String authorizationCode, URI redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("redirect_uri", redirectUri.toString());
        form.add("code", authorizationCode);
        if (properties.sendsClientSecret()) {
            form.add("client_secret", properties.clientSecret());
        }

        try {
            KakaoTokenResponse response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                log.warn("Kakao token exchange returned no usable access token");
                throw KakaoOAuthErrorMapper.codeExchangeFailed();
            }
            return response;
        } catch (RestClientResponseException exception) {
            log.warn("Kakao token exchange rejected with status {}", exception.getStatusCode().value());
            throw KakaoOAuthErrorMapper.tokenFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            log.warn("Kakao token endpoint unreachable");
            throw KakaoOAuthErrorMapper.unavailable(exception);
        } catch (OAuthProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Undecodable success body: the payload itself must not travel with the failure.
            log.warn("Kakao token response could not be decoded");
            throw KakaoOAuthErrorMapper.codeExchangeFailed();
        }
    }

    /**
     * Reads the Kakao service user identifier.
     *
     * <p>This request is idempotent, so a single retry is safe — but only when no response was ever
     * produced, which is why it is limited to a refused connection. A status response, including
     * 4xx, is an answer and is never retried.
     */
    KakaoUserResponse fetchUser(String kakaoAccessToken) {
        try {
            return requestUser(kakaoAccessToken);
        } catch (ResourceAccessException firstFailure) {
            if (!causedByRefusedConnection(firstFailure)) {
                log.warn("Kakao user-info endpoint unreachable");
                throw KakaoOAuthErrorMapper.unavailable(firstFailure);
            }
            return retryUserOnce(kakaoAccessToken);
        } catch (RestClientResponseException exception) {
            log.warn("Kakao user-info rejected with status {}", exception.getStatusCode().value());
            throw KakaoOAuthErrorMapper.userInfoFailure(exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn("Kakao user-info response could not be decoded");
            throw KakaoOAuthErrorMapper.principalUnverified();
        }
    }

    private KakaoUserResponse retryUserOnce(String kakaoAccessToken) {
        try {
            return requestUser(kakaoAccessToken);
        } catch (ResourceAccessException exception) {
            log.warn("Kakao user-info endpoint unreachable after one retry");
            throw KakaoOAuthErrorMapper.unavailable(exception);
        } catch (RestClientResponseException exception) {
            log.warn("Kakao user-info rejected with status {}", exception.getStatusCode().value());
            throw KakaoOAuthErrorMapper.userInfoFailure(exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn("Kakao user-info response could not be decoded");
            throw KakaoOAuthErrorMapper.principalUnverified();
        }
    }

    private KakaoUserResponse requestUser(String kakaoAccessToken) {
        return restClient.get()
                .uri(properties.userInfoUri())
                .headers(headers -> headers.setBearerAuth(kakaoAccessToken))
                .retrieve()
                .body(KakaoUserResponse.class);
    }

    private boolean causedByRefusedConnection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
