package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthApiClient;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthErrorMapper;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProperties;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class KakaoOAuthApiClientRetryTest {

    @Test
    void userInfoRetriesExactlyOnceOnlyWhenTheCauseChainContainsConnectException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(2), requestTo("https://user.example/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "outer transport failure",
                            new IOException("nested", new ConnectException("connect")));
                });

        OAuthProviderException exception = catchThrowableOfType(
                () -> client(builder.build()).fetchUser("provider-access-token"),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        server.verify();
    }

    @Test
    void userInfoReadTimeoutDoesNotRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(1), requestTo("https://user.example/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new ResourceAccessException("read timeout", new SocketTimeoutException("read"));
                });

        OAuthProviderException exception = catchThrowableOfType(
                () -> client(builder.build()).fetchUser("provider-access-token"),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        server.verify();
    }

    @Test
    void tokenConnectionFailureDoesNotRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(1), requestTo("https://token.example/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new ResourceAccessException("connect", new ConnectException("connect"));
                });

        OAuthProviderException exception = catchThrowableOfType(
                () -> client(builder.build()).exchangeCode(
                        "authorization-code", URI.create("https://app.example.test/oauth/callback")),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        server.verify();
    }

    private KakaoOAuthApiClient client(RestClient restClient) {
        KakaoOAuthProperties properties = new KakaoOAuthProperties();
        properties.setClientId("test-client");
        properties.setAuthorizationUri("https://authorize.example/oauth/authorize");
        properties.setTokenUri("https://token.example/oauth/token");
        properties.setUserInfoUri("https://user.example/me");
        return new KakaoOAuthApiClient(restClient, properties, new KakaoOAuthErrorMapper());
    }
}
