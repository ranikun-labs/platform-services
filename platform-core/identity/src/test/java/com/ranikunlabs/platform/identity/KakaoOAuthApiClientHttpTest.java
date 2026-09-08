package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthApiClient;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthErrorMapper;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProperties;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class KakaoOAuthApiClientHttpTest {

    private static final URI REDIRECT_URI = URI.create("https://app.example.test/oauth/callback");

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void tokenRequestSendsCanonicalFormWithClientSecretAndNoPkceVerifier() throws Exception {
        server.enqueue(json("{\"access_token\":\"provider-token\",\"expires_in\":60}"));
        KakaoOAuthApiClient client = client("client-secret-value");

        assertThat(client.exchangeCode("authorization-code", REDIRECT_URI).accessToken())
                .isEqualTo("provider-token");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/token");
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(form(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "grant_type", "authorization_code",
                "client_id", "test-client",
                "client_secret", "client-secret-value",
                "redirect_uri", REDIRECT_URI.toString(),
                "code", "authorization-code"
        ));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void tokenRequestOmitsOptionalClientSecretWhenBlank(String clientSecret) throws Exception {
        server.enqueue(json("{\"access_token\":\"provider-token\"}"));
        KakaoOAuthApiClient client = client(clientSecret);

        client.exchangeCode("authorization-code", REDIRECT_URI);

        Map<String, String> form = form(server.takeRequest());
        assertThat(form).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "test-client")
                .containsEntry("redirect_uri", REDIRECT_URI.toString())
                .containsEntry("code", "authorization-code")
                .doesNotContainKey("client_secret")
                .doesNotContainKey("code_verifier");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void userInfoUsesGetAndBearerAndDecodesThePrivacyMinimalPrincipal() throws Exception {
        server.enqueue(json("{\"id\":123456789}"));
        KakaoOAuthProviderAdapter adapter = adapter(client(null));

        OAuthPrincipal principal = adapter.fetchPrincipal(
                new OAuthTokenGrant("provider-access-token", null, Instant.EPOCH), state());

        assertThat(principal).isEqualTo(new OAuthPrincipal("kakao", "123456789", null, false, null));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/user");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer provider-access-token");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"access_token\":null}",
            "{\"access_token\":\"   \"}",
            "{"
    })
    void missingBlankOrMalformedTokenResponseFailsClosed(String responseBody) {
        server.enqueue(json(responseBody));

        OAuthProviderException exception = catchThrowableOfType(
                () -> client(null).exchangeCode("authorization-code", REDIRECT_URI),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"id\":null}",
            "{\"id\":0}",
            "{\"id\":-1}",
            "{"
    })
    void missingInvalidOrMalformedUserResponseFailsClosed(String responseBody) {
        server.enqueue(json(responseBody));

        OAuthProviderException exception = catchThrowableOfType(
                () -> adapter(client(null)).fetchPrincipal(
                        new OAuthTokenGrant("provider-access-token", null, Instant.EPOCH), state()),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500})
    void tokenHttpFailuresMapToNeutralReasonsWithoutRetryOrResponseLeakage(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("sensitive-provider-body"));

        OAuthProviderException exception = catchThrowableOfType(
                () -> client("client-secret-value").exchangeCode("authorization-code", REDIRECT_URI),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        assertThat(exception).hasMessage(null).hasNoCause();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500})
    void userInfoHttpFailuresMapToNeutralReasonsWithoutRetryOrResponseLeakage(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("sensitive-provider-body"));

        OAuthProviderException exception = catchThrowableOfType(
                () -> client(null).fetchUser("provider-access-token"),
                OAuthProviderException.class);

        assertThat(exception.reason()).isEqualTo(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        assertThat(exception).hasMessage(null).hasNoCause();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private KakaoOAuthApiClient client(String clientSecret) {
        KakaoOAuthProperties properties = properties();
        properties.setClientSecret(clientSecret);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofMillis(500));
        return new KakaoOAuthApiClient(
                RestClient.builder().requestFactory(factory).build(),
                properties,
                new KakaoOAuthErrorMapper());
    }

    private KakaoOAuthProviderAdapter adapter(KakaoOAuthApiClient client) {
        return new KakaoOAuthProviderAdapter(
                client,
                properties(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
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
        return new OAuthStateRecord("kakao", REDIRECT_URI, "/", null, null, Instant.EPOCH);
    }

    private MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private Map<String, String> form(RecordedRequest request) {
        return Arrays.stream(request.getBody().readUtf8().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8)
                ));
    }
}
