package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthApiClient;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthErrorMapper;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProperties;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.dto.KakaoUserResponse;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoOAuthAdapterTest {

    private MockWebServer server;
    private KakaoOAuthApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = client(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void authorizationUrlContainsOnlyKakaoRequiredValuesAndPkce() {
        KakaoOAuthProviderAdapter adapter = adapterWithClientId(1L);
        URI url = adapter.buildAuthorizationUrl(new OAuthAuthorizationRequest(
                "kakao", URI.create("https://app.example.test/callback"),
                "state-value", "challenge", "S256", null));

        assertThat(UriComponentsBuilder.fromUri(url).build().getQueryParams())
                .containsEntry("client_id", java.util.List.of("test-client"))
                .containsEntry("redirect_uri", java.util.List.of("https://app.example.test/callback"))
                .containsEntry("response_type", java.util.List.of("code"))
                .containsEntry("state", java.util.List.of("state-value"))
                .containsEntry("code_challenge", java.util.List.of("challenge"))
                .containsEntry("code_challenge_method", java.util.List.of("S256"));
        assertThat(url.toString()).doesNotContain("scope=", "nonce=");
    }

    @Test
    void tokenExchangeSendsFormAndProviderAdapterMapsExpiry() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"token\",\"expires_in\":60}")
                .addHeader("Content-Type", "application/json"));
        KakaoOAuthProviderAdapter adapter = adapterWithClientId(1L);

        OAuthTokenGrant grant = adapter.exchangeCode("auth-code", URI.create("https://app.example/callback"), "verifier");

        assertThat(grant.accessToken()).isEqualTo("token");
        assertThat(grant.expiresAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
        RecordedRequest request = server.takeRequest();
        Map<String, String> form = form(request);
        assertThat(form).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "test-client")
                .containsEntry("code", "auth-code")
                .containsEntry("code_verifier", "verifier");
    }

    @Test
    void malformedOrErrorProviderResponsesBecomeNeutralFailuresWithoutResponseBody() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("sensitive-provider-body"));

        assertThatThrownBy(() -> client.exchangeCode("code", URI.create("https://app.example/callback"), null))
                .isInstanceOfSatisfying(OAuthProviderException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
                    assertThat(exception).hasMessage(null);
                });
    }

    @Test
    void userInfoUsesBearerAndOnlyMapsProviderSubject() {
        KakaoOAuthProviderAdapter adapter = adapterWithClientId(123456789L);

        OAuthPrincipal principal = adapter.fetchPrincipal(
                new OAuthTokenGrant("provider-token", null, Instant.EPOCH), state());

        assertThat(principal).isEqualTo(new OAuthPrincipal("kakao", "123456789", null, false, null));
    }

    @Test
    void missingKakaoSubjectFailsClosed() {
        KakaoOAuthProviderAdapter adapter = adapterWithClientId(null);

        assertThatThrownBy(() -> adapter.fetchPrincipal(
                new OAuthTokenGrant("provider-token", null, Instant.EPOCH), state()))
                .isInstanceOfSatisfying(OAuthProviderException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED));
    }

    private KakaoOAuthProviderAdapter adapterWithClientId(Long id) {
        KakaoOAuthProperties properties = properties();
        KakaoOAuthApiClient api = new KakaoOAuthApiClient(RestClient.create(), properties,
                new KakaoOAuthErrorMapper()) {
            @Override
            public KakaoUserResponse fetchUser(String accessToken) {
                return new KakaoUserResponse(id);
            }
        };
        return new KakaoOAuthProviderAdapter(api, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private KakaoOAuthApiClient client(String secret) {
        KakaoOAuthProperties properties = properties();
        properties.setClientSecret(secret);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofMillis(500));
        return new KakaoOAuthApiClient(RestClient.builder().requestFactory(factory).build(), properties,
                new KakaoOAuthErrorMapper());
    }

    private KakaoOAuthProperties properties() {
        KakaoOAuthProperties properties = new KakaoOAuthProperties();
        properties.setClientId("test-client");
        properties.setAuthorizationUri(server.url("/authorize").toString());
        properties.setTokenUri(server.url("/token").toString());
        properties.setUserInfoUri(server.url("/user").toString());
        return properties;
    }

    private OAuthStateRecord state() {
        return new OAuthStateRecord("kakao", URI.create("https://app.example/callback"), "/", null, null, Instant.EPOCH);
    }

    private Map<String, String> form(RecordedRequest request) {
        return Arrays.stream(request.getBody().readUtf8().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8)));
    }
}
