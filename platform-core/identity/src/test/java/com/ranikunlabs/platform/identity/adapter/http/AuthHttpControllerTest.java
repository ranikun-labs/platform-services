package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.request.LoginRequest;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStoreUnavailableException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthHttpControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private AuthService authService;
    @Mock
    private OAuthAuthorizationService oauthAuthorizationService;
    @Mock
    private OAuthLoginService oauthLoginService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(
                        authService,
                        oauthAuthorizationService,
                        oauthLoginService,
                        new OAuthHttpResponseMapper()))
                .setControllerAdvice(new IdentityHttpExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsCanonicalTokenEnvelopeAndDelegatesOnlyTranslatedFields() throws Exception {
        when(authService.login("manager", "password123"))
                .thenReturn(new AuthTokenBundle("access-token", "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"manager","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("요청에 성공하였습니다."))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));

        verify(authService).login("manager", "password123");
    }

    @Test
    void unknownLoginAndWrongPasswordRemainIndistinguishable() throws Exception {
        doThrow(new InvalidCredentialsException())
                .when(authService).login("unknown", "password123");
        doThrow(new InvalidCredentialsException())
                .when(authService).login("manager", "wrong-password");

        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"unknown","password":"password123"}
                                """))
                .andReturn();
        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"manager","password":"wrong-password"}
                                """))
                .andReturn();

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString())
                .contains("아이디 또는 비밀번호가 일치하지 않습니다.")
                .doesNotContain("unknown", "manager", "password");
    }

    @Test
    void loginMapsMissingProductProfileToNotFound() throws Exception {
        doThrow(new CRMIdentityProjectionException(
                CRMIdentityProjectionException.Reason.PRODUCT_PROFILE_NOT_FOUND))
                .when(authService).login("manager", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"manager\",\"password\":\"password123\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product identity 정보를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = CRMIdentityProjectionException.Reason.class, names = {
            "DEPENDENCY_AUTH_FAILURE",
            "DEPENDENCY_UNAVAILABLE",
            "DEPENDENCY_CONTRACT_FAILURE"
    })
    void loginMapsProductDependencyFailuresToServiceUnavailable(
            CRMIdentityProjectionException.Reason reason
    ) throws Exception {
        doThrow(new CRMIdentityProjectionException(reason))
                .when(authService).login("manager", "password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"manager\",\"password\":\"password123\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Product identity 정보를 사용할 수 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(reason.name(), "CRMIdentityProjectionException");
    }

    @Test
    void loginValidationUsesCanonicalStatusAndFirstFieldMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("사용자 ID는 필수입니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService, never()).login(any(), any());
    }

    @Test
    void refreshReturnsRotatedTokensAndPreservesBodyTransport() throws Exception {
        when(authService.refreshToken("old-refresh"))
                .thenReturn(new AuthTokenBundle("new-access", "new-refresh"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));

        verify(authService).refreshToken("old-refresh");
    }

    @Test
    void refreshValidationUsesCanonicalStatusAndDoesNotCallTheApplicationService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Refresh Token은 필수입니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService, never()).refreshToken(any());
    }

    @ParameterizedTest
    @EnumSource(TokenLifecycleException.Reason.class)
    void refreshMapsTypedTokenFailuresToCanonicalContract(TokenLifecycleException.Reason reason)
            throws Exception {
        doThrow(new TokenLifecycleException(reason)).when(authService).refreshToken("refresh-token");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-token\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus(reason));
        assertThat(result.getResponse().getContentAsString())
                .contains(expectedMessage(reason))
                .doesNotContain("TokenLifecycleException");
    }

    @Test
    void logoutUsesVerifiedAuthenticationSubjectAndReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .principal(new UsernamePasswordAuthenticationToken(
                                ACCOUNT_ID.toString(), null, List.of()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(authService).logout(ACCOUNT_ID, "access-token");
    }

    @Test
    void logoutPreservesCanonicalNonBearerNoContentBehavior() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .principal(new UsernamePasswordAuthenticationToken(
                                ACCOUNT_ID.toString(), null, List.of()))
                        .header(HttpHeaders.AUTHORIZATION, "Basic credentials"))
                .andExpect(status().isNoContent());

        verify(authService, never()).logout(any(), any());
    }

    @Test
    void authorizationReturnsOnlyTheProviderUrlAndForwardsTheLegacyChannelRequest() throws Exception {
        when(oauthAuthorizationService.startAuthorization(any()))
                .thenReturn(new OAuthAuthorizationService.AuthorizationUrlResult(
                        URI.create("https://kauth.kakao.com/oauth/authorize?state=server-state"),
                        "server-state"));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientChannel":"MOBILE","returnTo":"/journals/42"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value("https://kauth.kakao.com/oauth/authorize?state=server-state"))
                .andExpect(jsonPath("$.data.state").doesNotExist())
                .andExpect(jsonPath("$.data.codeVerifier").doesNotExist())
                .andExpect(jsonPath("$.data.nonce").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        ArgumentCaptor<OAuthAuthorizationCommand> command =
                ArgumentCaptor.forClass(OAuthAuthorizationCommand.class);
        verify(oauthAuthorizationService).startAuthorization(command.capture());
        assertThat(command.getValue())
                .isEqualTo(new OAuthAuthorizationCommand("kakao", ClientChannel.MOBILE, "/journals/42"));
    }

    @Test
    void authorizationValidationPreservesChannelAndReturnToMessages() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"returnTo\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("OAuth 클라이언트 채널은 필수입니다."));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("로그인 완료 후 이동 경로는 필수입니다."));

        verify(oauthAuthorizationService, never()).startAuthorization(any());
    }

    @ParameterizedTest
    @EnumSource(OAuthRequestException.Reason.class)
    void authorizationMapsEveryIdentityOwnedRequestFailure(
            OAuthRequestException.Reason reason
    ) throws Exception {
        when(oauthAuthorizationService.startAuthorization(any()))
                .thenThrow(new OAuthRequestException(reason));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(expectedMessage(reason)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(ProductClientLookupException.Reason.class)
    void authorizationMapsEveryProductClientLookupFailure(
            ProductClientLookupException.Reason reason
    ) throws Exception {
        when(oauthAuthorizationService.startAuthorization(any()))
                .thenThrow(new ProductClientLookupException(reason));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().is(expectedStatus(reason)))
                .andExpect(jsonPath("$.status").value(expectedStatus(reason)))
                .andExpect(jsonPath("$.message").value(expectedMessage(reason)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void authorizationMapsInvalidReturnToWithoutCallingProviderFromHttp() throws Exception {
        when(oauthAuthorizationService.startAuthorization(any()))
                .thenThrow(new OAuthRequestException(OAuthRequestException.Reason.INVALID_OAUTH_RETURN_TO));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("유효하지 않은 로그인 완료 후 이동 경로입니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void exchangeReturnsOnlyIdentityTokensForAnExistingLinkedAccount() throws Exception {
        when(oauthLoginService.completeLogin(any()))
                .thenReturn(new OAuthLoginResult.ExistingAccountAuthenticated(
                        ACCOUNT_ID, new AuthTokenBundle("oauth-access", "oauth-refresh")));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"authorization-code\",\"state\":\"callback-state\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("oauth-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("oauth-refresh"))
                .andExpect(jsonPath("$.data.providerToken").doesNotExist());

        ArgumentCaptor<OAuthCallbackCommand> command = ArgumentCaptor.forClass(OAuthCallbackCommand.class);
        verify(oauthLoginService).completeLogin(command.capture());
        assertThat(command.getValue())
                .isEqualTo(new OAuthCallbackCommand("kakao", "authorization-code", "callback-state"));
    }

    @Test
    void exchangeValidationPreservesCodeAndStateMessages() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/kakao/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"callback-state\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인가 코드는 필수입니다."));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"authorization-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("state는 필수입니다"));

        verify(oauthLoginService, never()).completeLogin(any());
    }

    @Test
    void exchangeMapsOnboardingWithoutExposingTheCandidate() throws Exception {
        when(oauthLoginService.completeLogin(any()))
                .thenReturn(new OAuthLoginResult.NewAccountOnboardingRequired(null));

        mockMvc.perform(exchangeRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("연결된 Carelog 계정이 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(OAuthLoginResult.ConflictReason.class)
    void exchangeMapsIdentityConflictsToOnePublicConflict(
            OAuthLoginResult.ConflictReason reason
    ) throws Exception {
        when(oauthLoginService.completeLogin(any()))
                .thenReturn(new OAuthLoginResult.ExternalIdentityConflict(reason));

        mockMvc.perform(exchangeRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("OAuth 계정 연결 상태가 유효하지 않습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void exchangeMapsProviderAndStateFailuresWithoutLeakingInternalReasons() throws Exception {
        when(oauthLoginService.completeLogin(any()))
                .thenReturn(new OAuthLoginResult.ProviderAuthenticationFailed(
                        OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED));
        mockMvc.perform(exchangeRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("OAuth 인증에 실패했습니다."));

        when(oauthLoginService.completeLogin(any()))
                .thenReturn(new OAuthLoginResult.InvalidOrExpiredState());
        mockMvc.perform(exchangeRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 인증 요청입니다."));

        doThrow(new OAuthProviderException(
                OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE,
                new IllegalStateException("provider response secret")))
                .when(oauthLoginService).completeLogin(any());
        MvcResult providerException = mockMvc.perform(exchangeRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("OAuth 인증에 실패했습니다."))
                .andReturn();
        assertThat(providerException.getResponse().getContentAsString())
                .doesNotContain("provider response secret");
    }

    @Test
    void exchangeMapsStateStoreOutageToServiceUnavailable() throws Exception {
        doThrow(new OAuthStateStoreUnavailableException(
                "redis://secret-host:6379 unavailable",
                new IllegalStateException("redis-secret")))
                .when(oauthLoginService).completeLogin(any());

        mockMvc.perform(exchangeRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("OAuth 인증 요청 저장소를 사용할 수 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void exchangeUnexpectedFailureUsesSafeInternalServerErrorContract() throws Exception {
        doThrow(new IllegalStateException("JWT implementation detail"))
                .when(oauthLoginService).completeLogin(any());

        mockMvc.perform(exchangeRequest())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("서버에 예상치 못한 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void sensitiveHttpRequestToStringsAreRedacted() {
        assertThat(new LoginRequest("manager", "password123").toString())
                .doesNotContain("password123")
                .contains("password=REDACTED");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exchangeRequest() {
        return post("/api/v1/auth/oauth/kakao/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"authorization-code\",\"state\":\"callback-state\"}");
    }

    private static int expectedStatus(TokenLifecycleException.Reason reason) {
        return reason == TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND ? 404 : 401;
    }

    private static String expectedMessage(TokenLifecycleException.Reason reason) {
        return switch (reason) {
            case INVALID_REFRESH_TOKEN -> "유효하지 않은 Refresh Token입니다.";
            case REFRESH_TOKEN_EXPIRED -> "만료된 Refresh Token입니다.";
            case REFRESH_TOKEN_NOT_FOUND -> "Refresh Token을 찾을 수 없습니다.";
        };
    }

    private static String expectedMessage(OAuthRequestException.Reason reason) {
        return switch (reason) {
            case UNSUPPORTED_OAUTH_PROVIDER -> "지원하지 않는 OAuth 제공자입니다.";
            case UNSUPPORTED_CLIENT_CHANNEL -> "지원하지 않는 OAuth 클라이언트 채널입니다.";
            case INVALID_PRODUCT_CLIENT_CHANNEL_MAPPING -> "제품 인증 Client와 채널 조합이 유효하지 않습니다.";
            case INVALID_OAUTH_RETURN_TO -> "유효하지 않은 로그인 완료 후 이동 경로입니다.";
        };
    }

    private static int expectedStatus(ProductClientLookupException.Reason reason) {
        return switch (reason) {
            case INVALID_PRODUCT_CLIENT_ID -> 400;
            case UNKNOWN_PRODUCT_CLIENT -> 401;
            case DISABLED_PRODUCT_CLIENT -> 403;
        };
    }

    private static String expectedMessage(ProductClientLookupException.Reason reason) {
        return switch (reason) {
            case INVALID_PRODUCT_CLIENT_ID -> "유효하지 않은 제품 인증 Client입니다.";
            case UNKNOWN_PRODUCT_CLIENT -> "알 수 없는 제품 인증 Client입니다.";
            case DISABLED_PRODUCT_CLIENT -> "비활성화된 제품 인증 Client입니다.";
        };
    }
}
