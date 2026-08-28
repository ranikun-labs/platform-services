package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto.KakaoTokenResponse;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto.KakaoUserResponse;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.URI;
import java.util.Optional;

/** Encapsulates Kakao token and user-info HTTP exchange behind the provider adapter boundary. */
@Conditional(KakaoOAuthConfiguredCondition.class)
public class KakaoOAuthApiClient {

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;
    private final KakaoOAuthErrorMapper errorMapper;

    public KakaoOAuthApiClient(RestClient kakaoOAuthRestClient,
                               KakaoOAuthProperties properties,
                               KakaoOAuthErrorMapper errorMapper) {
        this.restClient = kakaoOAuthRestClient;
        this.properties = properties;
        this.errorMapper = errorMapper;
    }

    public KakaoTokenResponse exchangeCode(String code, URI redirectUri, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.getClientId());
        form.add("redirect_uri", redirectUri.toString());
        form.add("code", code);
        optional(properties.getClientSecret()).ifPresent(value -> form.add("client_secret", value));
        if (codeVerifier != null) {
            form.add("code_verifier", codeVerifier);
        }

        try {
            KakaoTokenResponse response = restClient.post().uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw errorMapper.codeExchangeFailure(null);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw errorMapper.tokenFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw errorMapper.unavailable(exception);
        } catch (OAuthProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw errorMapper.codeExchangeFailure(exception);
        }
    }

    public KakaoUserResponse fetchUser(String accessToken) {
        try {
            return userRequest(accessToken);
        } catch (ResourceAccessException firstFailure) {
            if (!hasConnectCause(firstFailure)) {
                throw errorMapper.unavailable(firstFailure);
            }
            return fetchUserRetry(accessToken);
        } catch (RestClientResponseException exception) {
            throw errorMapper.userInfoFailure(exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            throw errorMapper.principalUnverified(exception);
        }
    }

    private KakaoUserResponse fetchUserRetry(String accessToken) {
        try {
            return userRequest(accessToken);
        } catch (ResourceAccessException exception) {
            throw errorMapper.unavailable(exception);
        } catch (RestClientResponseException exception) {
            throw errorMapper.userInfoFailure(exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            throw errorMapper.principalUnverified(exception);
        }
    }

    private KakaoUserResponse userRequest(String accessToken) {
        return restClient.get()
                .uri(properties.getUserInfoUri())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(KakaoUserResponse.class);
    }

    private boolean hasConnectCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
