package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.IdentityAuthController;
import com.ranikunlabs.platform.identity.adapter.http.IdentityAuthExceptionHandler;
import com.ranikunlabs.platform.identity.adapter.http.security.IdentityAuthSecurityConfiguration;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import com.ranikunlabs.platform.identity.application.port.oauth.OnboardingCandidate;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Carelog-compatible Auth/OAuth HTTP contract and the Identity servlet security
 * boundary in a real Servlet Spring context.
 *
 * <p>The application services are mocked so this suite pins transport behavior only; their own
 * behavior is covered by the existing Identity application and persistence tests. No Kakao
 * credentials, Kakao Developers setup, or network access are required.
 */
@SpringBootTest(
        classes = IdentityAuthHttpCompatibilityTest.TestApplication.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@AutoConfigureMockMvc
class IdentityAuthHttpCompatibilityTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final String FOREIGN_JWT_SECRET = "98765432109876543210987654321098";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String AUTHORIZATION_PATH = "/api/v1/auth/oauth/kakao/authorization";
    private static final String EXCHANGE_PATH = "/api/v1/auth/oauth/kakao/exchange";

    private final MockMvc mockMvc;
    private final AuthService authService;
    private final OAuthAuthorizationService oAuthAuthorizationService;
    private final OAuthLoginService oAuthLoginService;
    private final JwtTokenProvider tokenProvider;

    @Autowired
    IdentityAuthHttpCompatibilityTest(
            MockMvc mockMvc,
            AuthService authService,
            OAuthAuthorizationService oAuthAuthorizationService,
            OAuthLoginService oAuthLoginService,
            TokenProviderPort tokenProvider
    ) {
        this.mockMvc = mockMvc;
        this.authService = authService;
        this.oAuthAuthorizationService = oAuthAuthorizationService;
        this.oAuthLoginService = oAuthLoginService;
        this.tokenProvider = (JwtTokenProvider) tokenProvider;
    }

    @BeforeEach
    void resetApplicationServices() {
        reset(authService, oAuthAuthorizationService, oAuthLoginService);
    }

    // ----- login -----

    @Test
    void loginReturnsTheCanonicalEnvelopeWithIssuedTokens() throws Exception {
        when(authService.login("admin", "password123"))
                .thenReturn(new AuthTokenBundle("issued-access", "issued-refresh"));

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("요청에 성공하였습니다."))
                .andExpect(jsonPath("$.data.accessToken").value("issued-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("issued-refresh"));
    }

    @Test
    void loginWithInvalidCredentialsUsesTheCanonicalUnauthorizedContract() throws Exception {
        when(authService.login("admin", "wrong")).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 일치하지 않습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void loginWithBlankUserIdFailsValidationWithTheCanonicalFieldMessage() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"  \",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("사용자 ID는 필수입니다."));
    }

    @Test
    void loginWithMalformedJsonUsesTheCanonicalBadRequestContract() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 값이 유효하지 않습니다."));
    }

    @Test
    void loginNeverEchoesTheSubmittedPassword() throws Exception {
        when(authService.login("admin", "super-secret-password"))
                .thenThrow(new InvalidCredentialsException());

        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin\",\"password\":\"super-secret-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("super-secret-password");
    }

    // ----- refresh -----

    @Test
    void refreshReturnsTheRotatedTokenPair() throws Exception {
        when(authService.refreshToken("old-refresh"))
                .thenReturn(new AuthTokenBundle("new-access", "new-refresh"));

        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));
    }

    @Test
    void invalidRefreshTokenIsUnauthorized() throws Exception {
        when(authService.refreshToken("bad")).thenThrow(
                new TokenLifecycleException(TokenLifecycleException.Reason.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않은 Refresh Token입니다."));
    }

    @Test
    void missingRefreshSessionIsNotFound() throws Exception {
        when(authService.refreshToken("rotated")).thenThrow(
                new TokenLifecycleException(TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND));

        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"rotated\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Refresh Token을 찾을 수 없습니다."));
    }

    @Test
    void expiredRefreshTokenIsUnauthorized() throws Exception {
        when(authService.refreshToken("expired")).thenThrow(
                new TokenLifecycleException(TokenLifecycleException.Reason.REFRESH_TOKEN_EXPIRED));

        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"expired\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("만료된 Refresh Token입니다."));
    }

    @Test
    void blankRefreshTokenFailsValidation() throws Exception {
        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh Token은 필수입니다."));
    }

    // ----- logout -----

    @Test
    void logoutPassesTheBearerSubjectAsTheAccountIdAndReturnsNoContent() throws Exception {
        UUID accountId = UUID.randomUUID();
        String accessToken = accessTokenFor(accountId);

        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        ArgumentCaptor<UUID> capturedAccountId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> capturedToken = ArgumentCaptor.forClass(String.class);
        verify(authService).logout(capturedAccountId.capture(), capturedToken.capture());
        assertThat(capturedAccountId.getValue()).isEqualTo(accountId);
        assertThat(capturedToken.getValue()).isEqualTo(accessToken);
    }

    @Test
    void logoutWithoutBearerIsUnauthorizedAndIsNotPublic() throws Exception {
        mockMvc.perform(post(LOGOUT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다. 로그인 후 다시 시도해주세요."))
                .andExpect(jsonPath("$.path").value(LOGOUT_PATH));
    }

    @Test
    void logoutWithAMalformedBearerTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A refresh token is issued by the same provider and the same signing key, so it is an
     * authentic token. It still must not be usable as a bearer credential.
     */
    @Test
    void aValidRefreshTokenCannotBeUsedAsABearerCredential() throws Exception {
        String refreshToken = tokenProvider.generateRefreshToken(UUID.randomUUID());

        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(authService, never()).logout(any(), any());
    }

    @Test
    void anExpiredAccessTokenCannotEstablishAuthentication() throws Exception {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
                JWT_SECRET, 900_000L, 1_209_600_000L,
                Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC));
        String expiredAccessToken = expiredProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "MEMBER", UUID.randomUUID());

        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", "Bearer " + expiredAccessToken))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).logout(any(), any());
    }

    @Test
    void logoutWithAForeignlySignedTokenCannotEstablishAuthentication() throws Exception {
        JwtTokenProvider foreignProvider = new JwtTokenProvider(
                FOREIGN_JWT_SECRET, 900_000L, 1_209_600_000L, Clock.systemUTC());
        String forgedToken = foreignProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "MEMBER", UUID.randomUUID());

        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());
    }

    // ----- OAuth authorization -----

    @Test
    void authorizationStartReachesTheProviderNeutralServiceAndReturnsOnlyTheUrl() throws Exception {
        when(oAuthAuthorizationService.startAuthorization(any())).thenReturn(
                new OAuthAuthorizationService.AuthorizationUrlResult(
                        URI.create("https://kauth.example.test/oauth/authorize?client_id=x"),
                        "server-side-state"));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value("https://kauth.example.test/oauth/authorize?client_id=x"))
                // The server-side state must never leave the state store through this response.
                .andExpect(jsonPath("$.data.state").doesNotExist());

        ArgumentCaptor<OAuthAuthorizationCommand> command =
                ArgumentCaptor.forClass(OAuthAuthorizationCommand.class);
        verify(oAuthAuthorizationService).startAuthorization(command.capture());
        assertThat(command.getValue().provider()).isEqualTo("kakao");
        assertThat(command.getValue().clientChannel()).isEqualTo(ClientChannel.WEB);
        assertThat(command.getValue().returnTo()).isEqualTo("/");
    }

    @Test
    void authorizationStartRejectsAnUnsupportedClientChannelValue() throws Exception {
        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"DESKTOP\",\"returnTo\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 값이 유효하지 않습니다."));
    }

    @Test
    void authorizationStartMapsAnInvalidReturnToToTheCanonicalBadRequest() throws Exception {
        when(oAuthAuthorizationService.startAuthorization(any())).thenThrow(
                new OAuthAuthorizationRequestException(
                        OAuthAuthorizationRequestException.Reason.INVALID_OAUTH_RETURN_TO));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 로그인 완료 후 이동 경로입니다."));
    }

    @Test
    void authorizationStartMapsAnInvalidProductClientChannelMapping() throws Exception {
        when(oAuthAuthorizationService.startAuthorization(any())).thenThrow(
                new OAuthAuthorizationRequestException(
                        OAuthAuthorizationRequestException.Reason.INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"MOBILE\",\"returnTo\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("제품 인증 Client와 채널 조합이 유효하지 않습니다."));
    }

    @Test
    void authorizationStartMapsTheFailClosedProductClientLookupReasons() throws Exception {
        when(oAuthAuthorizationService.startAuthorization(any())).thenThrow(
                new ProductClientLookupException(
                        ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));
        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("알 수 없는 제품 인증 Client입니다."));

        reset(oAuthAuthorizationService);
        when(oAuthAuthorizationService.startAuthorization(any())).thenThrow(
                new ProductClientLookupException(
                        ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT));
        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("비활성화된 제품 인증 Client입니다."));
    }

    @Test
    void authorizationStartReportsAStateStoreOutageAsUnavailableRatherThanAuthFailure() throws Exception {
        when(oAuthAuthorizationService.startAuthorization(any())).thenThrow(
                new OAuthStateStoreUnavailableException("redis down", new IllegalStateException("connection refused")));

        String body = mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("OAuth 인증 요청 저장소를 사용할 수 없습니다."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("connection refused");
    }

    // ----- OAuth exchange -----

    @Test
    void exchangeReturnsTokensForAnAuthenticatedExistingAccount() throws Exception {
        when(oAuthLoginService.completeLogin(any())).thenReturn(
                new OAuthLoginResult.ExistingAccountAuthenticated(
                        UUID.randomUUID(), new AuthTokenBundle("oauth-access", "oauth-refresh")));

        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("oauth-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("oauth-refresh"));

        ArgumentCaptor<OAuthCallbackCommand> command = ArgumentCaptor.forClass(OAuthCallbackCommand.class);
        verify(oAuthLoginService).completeLogin(command.capture());
        assertThat(command.getValue().provider()).isEqualTo("kakao");
        assertThat(command.getValue().authorizationCode()).isEqualTo("kakao-code");
        assertThat(command.getValue().state()).isEqualTo("kakao-state");
    }

    @Test
    void exchangeDoesNotLinkAnAccountWhenOnboardingIsRequired() throws Exception {
        when(oAuthLoginService.completeLogin(any())).thenReturn(
                new OAuthLoginResult.NewAccountOnboardingRequired(new OnboardingCandidate(
                        "kakao", "provider-subject", "candidate@example.com", true, "후보", "/")));

        String body = mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("연결된 Carelog 계정이 없습니다."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The candidate's provider identity and email must not leak into the failure body.
        assertThat(body).doesNotContain("candidate@example.com").doesNotContain("provider-subject");
    }

    @Test
    void exchangeMapsBothIdentityConflictReasonsToTheCanonicalConflict() throws Exception {
        when(oAuthLoginService.completeLogin(any())).thenReturn(
                new OAuthLoginResult.ExternalIdentityConflict(
                        OAuthLoginResult.ConflictReason.ACCOUNT_INACTIVE));
        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("OAuth 계정 연결 상태가 유효하지 않습니다."));

        reset(oAuthLoginService);
        when(oAuthLoginService.completeLogin(any())).thenReturn(
                new OAuthLoginResult.ExternalIdentityConflict(
                        OAuthLoginResult.ConflictReason.ORPHANED_IDENTITY));
        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("OAuth 계정 연결 상태가 유효하지 않습니다."));
    }

    @Test
    void exchangeMapsProviderAuthenticationFailureToTheCanonicalUnauthorized() throws Exception {
        when(oAuthLoginService.completeLogin(any())).thenReturn(
                new OAuthLoginResult.ProviderAuthenticationFailed(
                        OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED));

        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("OAuth 인증에 실패했습니다."));
    }

    @Test
    void exchangeMapsInvalidOrExpiredStateToTheCanonicalUnauthorized() throws Exception {
        when(oAuthLoginService.completeLogin(any())).thenReturn(new OAuthLoginResult.InvalidOrExpiredState());

        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"kakao-code\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 인증 요청입니다."));
    }

    @Test
    void exchangeRequiresBothCodeAndState() throws Exception {
        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"state\":\"kakao-state\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인가 코드는 필수입니다."));
    }

    // ----- security boundary -----

    @Test
    void theExactPublicPostEndpointsDoNotRequireAuthentication() throws Exception {
        when(authService.login(any(), any())).thenReturn(new AuthTokenBundle("a", "r"));
        when(authService.refreshToken(any())).thenReturn(new AuthTokenBundle("a", "r"));
        when(oAuthAuthorizationService.startAuthorization(any())).thenReturn(
                new OAuthAuthorizationService.AuthorizationUrlResult(
                        URI.create("https://kauth.example.test/authorize"), "state"));
        when(oAuthLoginService.completeLogin(any())).thenReturn(new OAuthLoginResult.InvalidOrExpiredState());

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"token\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isOk());
        // Reached the application service rather than being rejected as unauthenticated.
        mockMvc.perform(post(EXCHANGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code\",\"state\":\"state\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 인증 요청입니다."));
    }

    @Test
    void anUnknownAuthNamespaceEndpointIsNotAccidentallyPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void theLoginPathIsPublicOnlyForItsExactPostMethod() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login/extra"))
                .andExpect(status().isUnauthorized());
    }

    private String accessTokenFor(UUID accountId) {
        return tokenProvider.generateAccessToken(
                accountId, UUID.randomUUID(), "MEMBER", UUID.randomUUID());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            IdentityAuthController.class,
            IdentityAuthExceptionHandler.class,
            IdentityAuthSecurityConfiguration.class
    })
    static class TestApplication {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        OAuthAuthorizationService oAuthAuthorizationService() {
            return mock(OAuthAuthorizationService.class);
        }

        @Bean
        OAuthLoginService oAuthLoginService() {
            return mock(OAuthLoginService.class);
        }

        /** A real provider so bearer authentication is proven against genuine signature checks. */
        @Bean
        TokenProviderPort tokenProviderPort() {
            return new JwtTokenProvider(JWT_SECRET, 900_000L, 1_209_600_000L, Clock.systemUTC());
        }
    }
}
