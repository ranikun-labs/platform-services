package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthPrincipal;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthTokenGrant;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production Kakao adapter against a local HTTP server.
 *
 * <p>No Kakao REST API key, client secret, account, authorization code, or network access is
 * involved; every round trip terminates at a deterministic in-process fixture.
 */
class KakaoOAuthProviderAdapterTest {

    private static final String CLIENT_ID = "test-rest-api-key-marker";
    private static final String CLIENT_SECRET = "test-client-secret-marker";
    private static final String AUTHORIZATION_CODE = "test-authorization-code-marker";
    private static final String KAKAO_ACCESS_TOKEN = "test-kakao-access-token-marker";
    private static final URI REDIRECT_URI = URI.create("http://localhost:5173/oauth/kakao/callback");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<StubResponse> tokenResponse = new AtomicReference<>();
    private final AtomicReference<StubResponse> userResponse = new AtomicReference<>();

    @BeforeEach
    void startKakaoStub() throws IOException {
        tokenResponse.set(StubResponse.json(200,
                "{\"access_token\":\"" + KAKAO_ACCESS_TOKEN + "\",\"expires_in\":21599}"));
        userResponse.set(StubResponse.json(200, "{\"id\":1234567890}"));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, tokenResponse.get()));
        server.createContext("/v2/user/me", exchange -> respond(exchange, userResponse.get()));
        server.start();
    }

    @AfterEach
    void stopKakaoStub() {
        server.stop(0);
    }

    // ----- authorization URL -----

    @Test
    void buildsTheDocumentedAuthorizationRequestAndNothingElse() {
        OAuthProviderPort provider = adapter(properties(server.getAddress().getPort(), true));

        URI authorizationUrl = provider.buildAuthorizationUrl(new OAuthAuthorizationRequest(
                "kakao", REDIRECT_URI, "platform-state-value", null, null, null));

        assertThat(provider.providerCode()).isEqualTo("kakao");
        assertThat(authorizationUrl.toString())
                .startsWith("https://kauth.kakao.com/oauth/authorize?");
        Map<String, String> query = queryOf(authorizationUrl);
        assertThat(query).containsOnlyKeys("client_id", "redirect_uri", "response_type", "state");
        assertThat(query.get("client_id")).isEqualTo(CLIENT_ID);
        assertThat(query.get("redirect_uri")).isEqualTo(REDIRECT_URI.toString());
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("state")).isEqualTo("platform-state-value");
        assertThat(authorizationUrl.toString()).doesNotContain(CLIENT_SECRET);
    }

    /**
     * The ordinary Kakao REST Login contract defines neither PKCE nor an OIDC nonce, so the core
     * must not generate them and the adapter must not send them.
     */
    @Test
    void declaresNeitherPkceNorNonceForThisNonOidcFlow() {
        OAuthProviderPort provider = adapter(properties(server.getAddress().getPort(), true));

        assertThat(provider.supportsPkce()).isFalse();
        assertThat(provider.requiresNonce()).isFalse();

        // Even if a challenge were supplied, it never reaches Kakao's authorize request.
        URI authorizationUrl = provider.buildAuthorizationUrl(new OAuthAuthorizationRequest(
                "kakao", REDIRECT_URI, "state", "a-code-challenge", "S256", "a-nonce"));
        assertThat(queryOf(authorizationUrl))
                .doesNotContainKeys("code_challenge", "code_challenge_method", "nonce", "scope",
                        "prompt", "login_hint");
    }

    // ----- token exchange -----

    @Test
    void exchangesTheCodeWithTheDocumentedFormParametersIncludingTheClientSecret() {
        OAuthTokenGrant grant = adapter(properties(server.getAddress().getPort(), true))
                .exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null);

        assertThat(grant.accessToken()).isEqualTo(KAKAO_ACCESS_TOKEN);
        assertThat(grant.idToken()).isNull();
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(21599));

        RecordedRequest tokenRequest = onlyRequestTo("/oauth/token");
        assertThat(tokenRequest.method()).isEqualTo("POST");
        assertThat(tokenRequest.contentType()).startsWith("application/x-www-form-urlencoded");
        Map<String, String> form = formOf(tokenRequest.body());
        assertThat(form).containsOnlyKeys(
                "grant_type", "client_id", "redirect_uri", "code", "client_secret");
        assertThat(form.get("grant_type")).isEqualTo("authorization_code");
        assertThat(form.get("client_id")).isEqualTo(CLIENT_ID);
        assertThat(form.get("redirect_uri")).isEqualTo(REDIRECT_URI.toString());
        assertThat(form.get("code")).isEqualTo(AUTHORIZATION_CODE);
        assertThat(form.get("client_secret")).isEqualTo(CLIENT_SECRET);
    }

    @Test
    void omitsTheClientSecretWhenTheKakaoApplicationHasItTurnedOff() {
        adapter(properties(server.getAddress().getPort(), false))
                .exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null);

        assertThat(formOf(onlyRequestTo("/oauth/token").body())).doesNotContainKey("client_secret");
    }

    @Test
    void neverSendsPkceMaterialEvenIfAVerifierIsPassed() {
        adapter(properties(server.getAddress().getPort(), true))
                .exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, "a-code-verifier");

        assertThat(formOf(onlyRequestTo("/oauth/token").body())).doesNotContainKey("code_verifier");
    }

    /** A one-time authorization code must never be replayed after an ambiguous outcome. */
    @Test
    void doesNotRetryTheTokenExchange() {
        tokenResponse.set(StubResponse.json(500, "{\"error\":\"server_error\"}"));

        assertThatThrownBy(() -> adapter(properties(server.getAddress().getPort(), true))
                .exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null))
                .isInstanceOf(OAuthProviderException.class);

        assertThat(requestsTo("/oauth/token")).hasSize(1);
    }

    @Test
    void mapsTokenExchangeFailuresOntoProviderNeutralReasons() {
        assertTokenStatusMapsTo(400, OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        assertTokenStatusMapsTo(401, OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        assertTokenStatusMapsTo(403, OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
        assertTokenStatusMapsTo(429, OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        assertTokenStatusMapsTo(500, OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        assertTokenStatusMapsTo(503, OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void treatsAnEmptyOrUndecodableTokenBodyAsAFailedExchange() {
        tokenResponse.set(StubResponse.json(200, "{\"expires_in\":21599}"));
        assertThat(tokenFailureReason()).isEqualTo(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);

        tokenResponse.set(StubResponse.json(200, "{\"access_token\":\"   \"}"));
        assertThat(tokenFailureReason()).isEqualTo(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);

        tokenResponse.set(StubResponse.json(200, "not-json"));
        assertThat(tokenFailureReason()).isEqualTo(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
    }

    @Test
    void reportsAnUnreachableTokenEndpointAsAnUnavailableProvider() throws IOException {
        OAuthProviderPort provider = adapter(properties(closedPort(), true));

        assertThat(reasonOf(() -> provider.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null)))
                .isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void reportsATokenReadTimeoutAsAnUnavailableProvider() {
        tokenResponse.set(StubResponse.delayed(Duration.ofSeconds(2)));
        OAuthProviderPort provider = adapter(
                properties(server.getAddress().getPort(), true, Duration.ofMillis(200)));

        assertThat(reasonOf(() -> provider.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null)))
                .isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
    }

    // ----- principal -----

    @Test
    void readsThePrincipalFromTheServiceUserIdAlone() {
        OAuthPrincipal principal = adapter(properties(server.getAddress().getPort(), true))
                .fetchPrincipal(grant(), state());

        assertThat(principal.provider()).isEqualTo("kakao");
        assertThat(principal.providerSubject()).isEqualTo("1234567890");
        assertThat(principal.email()).isNull();
        assertThat(principal.emailVerified()).isFalse();
        assertThat(principal.displayName()).isNull();

        RecordedRequest userRequest = onlyRequestTo("/v2/user/me");
        assertThat(userRequest.method()).isEqualTo("GET");
        assertThat(userRequest.authorization()).isEqualTo("Bearer " + KAKAO_ACCESS_TOKEN);
    }

    @Test
    void succeedsWhenKakaoReturnsNoEmailOrProfileFields() {
        userResponse.set(StubResponse.json(200,
                "{\"id\":42,\"connected_at\":\"2030-01-01T00:00:00Z\"}"));

        assertThat(adapter(properties(server.getAddress().getPort(), true))
                .fetchPrincipal(grant(), state()).providerSubject()).isEqualTo("42");
    }

    @Test
    void failsClosedForAnyUnusableServiceUserId() {
        for (String body : List.of("{}", "{\"id\":null}", "{\"id\":0}", "{\"id\":-1}")) {
            userResponse.set(StubResponse.json(200, body));
            assertThat(userInfoFailureReason())
                    .isEqualTo(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        }
        userResponse.set(StubResponse.json(200, "not-json"));
        assertThat(userInfoFailureReason())
                .isEqualTo(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
    }

    @Test
    void mapsUserInfoFailuresOntoProviderNeutralReasons() {
        assertUserStatusMapsTo(401, OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        assertUserStatusMapsTo(403, OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        assertUserStatusMapsTo(429, OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
        assertUserStatusMapsTo(500, OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void doesNotRetryUserInfoAfterAStatusResponse() {
        userResponse.set(StubResponse.json(401, "{\"code\":-401}"));

        assertThat(userInfoFailureReason())
                .isEqualTo(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
        assertThat(requestsTo("/v2/user/me")).hasSize(1);
    }

    @Test
    void reportsAnUnreachableUserInfoEndpointAsAnUnavailableProvider() throws IOException {
        OAuthProviderPort provider = adapter(properties(closedPort(), true));

        assertThat(reasonOf(() -> provider.fetchPrincipal(grant(), state())))
                .isEqualTo(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE);
    }

    // ----- secret safety -----

    @Test
    void neverExposesCredentialsOrProviderBodiesThroughFailures() {
        tokenResponse.set(StubResponse.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"" + AUTHORIZATION_CODE + "\"}"));
        OAuthProviderPort provider = adapter(properties(server.getAddress().getPort(), true));

        Throwable tokenFailure = catchFailure(
                () -> provider.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null));
        assertThat(renderedChain(tokenFailure))
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(AUTHORIZATION_CODE)
                .doesNotContain("invalid_grant");

        userResponse.set(StubResponse.json(401, "{\"msg\":\"" + KAKAO_ACCESS_TOKEN + "\"}"));
        Throwable userFailure = catchFailure(() -> provider.fetchPrincipal(grant(), state()));
        assertThat(renderedChain(userFailure))
                .doesNotContain(KAKAO_ACCESS_TOKEN)
                .doesNotContain(CLIENT_SECRET);
    }

    @Test
    void keepsCredentialsOutOfConfigurationDiagnostics() {
        String rendered = properties(server.getAddress().getPort(), true).toString();

        assertThat(rendered).doesNotContain(CLIENT_ID).doesNotContain(CLIENT_SECRET);
        assertThat(rendered).contains("clientId=REDACTED", "clientSecret=REDACTED");
    }

    // ----- fixtures -----

    private OAuthProviderPort adapter(KakaoOAuthProperties properties) {
        return new KakaoOAuthConfiguration().kakaoOAuthProviderAdapter(
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private KakaoOAuthProperties properties(int port, boolean clientSecretEnabled) {
        return properties(port, clientSecretEnabled, Duration.ofSeconds(3));
    }

    private KakaoOAuthProperties properties(int port, boolean clientSecretEnabled, Duration readTimeout) {
        return new KakaoOAuthProperties(
                CLIENT_ID,
                clientSecretEnabled ? CLIENT_SECRET : null,
                clientSecretEnabled,
                "https://kauth.kakao.com/oauth/authorize",
                "http://localhost:" + port + "/oauth/token",
                "http://localhost:" + port + "/v2/user/me",
                Duration.ofSeconds(2),
                readTimeout);
    }

    private OAuthTokenGrant grant() {
        return new OAuthTokenGrant(KAKAO_ACCESS_TOKEN, null, NOW.plusSeconds(3600));
    }

    private OAuthStateRecord state() {
        return new OAuthStateRecord("kakao", REDIRECT_URI, "/", null, null, NOW);
    }

    private void assertTokenStatusMapsTo(int status, OAuthLoginResult.FailureReason expected) {
        tokenResponse.set(StubResponse.json(status, "{\"error\":\"kakao\"}"));
        assertThat(tokenFailureReason()).as("token status %s", status).isEqualTo(expected);
    }

    private void assertUserStatusMapsTo(int status, OAuthLoginResult.FailureReason expected) {
        userResponse.set(StubResponse.json(status, "{\"code\":-1}"));
        assertThat(userInfoFailureReason()).as("user-info status %s", status).isEqualTo(expected);
    }

    private OAuthLoginResult.FailureReason tokenFailureReason() {
        OAuthProviderPort provider = adapter(properties(server.getAddress().getPort(), true));
        return reasonOf(() -> provider.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI, null));
    }

    private OAuthLoginResult.FailureReason userInfoFailureReason() {
        OAuthProviderPort provider = adapter(properties(server.getAddress().getPort(), true));
        return reasonOf(() -> provider.fetchPrincipal(grant(), state()));
    }

    private OAuthLoginResult.FailureReason reasonOf(Runnable call) {
        Throwable failure = catchFailure(call);
        assertThat(failure).isInstanceOf(OAuthProviderException.class);
        return ((OAuthProviderException) failure).reason();
    }

    private Throwable catchFailure(Runnable call) {
        try {
            call.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("Expected the call to fail");
    }

    private static String renderedChain(Throwable failure) {
        StringBuilder rendered = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            rendered.append(current).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return rendered.toString();
    }

    private RecordedRequest onlyRequestTo(String path) {
        List<RecordedRequest> matching = requestsTo(path);
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private List<RecordedRequest> requestsTo(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, String> queryOf(URI uri) {
        return splitPairs(uri.getRawQuery());
    }

    private static Map<String, String> formOf(String body) {
        return splitPairs(body);
    }

    private static Map<String, String> splitPairs(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(
                    java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private void respond(HttpExchange exchange, StubResponse configured) throws IOException {
        List<String> bodyLines = new ArrayList<>();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bodyLines.add(line);
            }
        }
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                String.join("", bodyLines)));

        if (configured.delay() != null) {
            try {
                Thread.sleep(configured.delay().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(configured.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
        exchange.close();
    }

    private record RecordedRequest(
            String method, String path, String contentType, String authorization, String body) {
    }

    private record StubResponse(int status, String body, Duration delay) {

        static StubResponse json(int status, String body) {
            return new StubResponse(status, body, null);
        }

        static StubResponse delayed(Duration delay) {
            return new StubResponse(200, "{}", delay);
        }
    }
}
