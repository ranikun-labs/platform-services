package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(IdentityHttpSecurityConfigurationTest.TestConfiguration.class)
class IdentityHttpSecurityConfigurationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TOKEN_SECRET =
            "identity-http-security-test-secret-key-long-enough-for-hmac-signing";

    @org.springframework.beans.factory.annotation.Autowired
    private WebApplicationContext webApplicationContext;

    @org.springframework.beans.factory.annotation.Autowired
    private AuthService authService;

    @org.springframework.beans.factory.annotation.Autowired
    private TokenProviderPort tokenProvider;

    @org.springframework.beans.factory.annotation.Autowired
    private OAuthAuthorizationService oauthAuthorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(authService, tokenProvider, oauthAuthorizationService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(new DelegatingFilterProxy("springSecurityFilterChain", webApplicationContext))
                .build();
    }

    @Test
    void passwordLoginIsPublicAndUsesTheCanonicalPath() throws Exception {
        when(authService.login("manager", "password123"))
                .thenReturn(new AuthTokenBundle("access-token", "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"userId\":\"manager\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));
    }

    @Test
    void registrationIsNotPublicByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/auth/carelog/registration").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exactOAuthPostRoutesArePublicButOtherMethodsRemainProtected() throws Exception {
        when(oauthAuthorizationService.startAuthorization(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OAuthAuthorizationService.AuthorizationUrlResult(
                        java.net.URI.create("https://provider.example/authorize"), "state"));

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType("application/json")
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/oauth/kakao/authorization"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/auth/oauth/kakao/exchange"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identitySecurityChainDoesNotImposeAuthenticationOutsideItsRouteScope() throws Exception {
        mockMvc.perform(get("/api/v1/not-owned-by-identity"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutWithoutBearerTokenUsesCanonicalUnauthorizedSecurityBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다. 로그인 후 다시 시도해주세요."))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/logout"));

        verifyNoInteractions(authService);
    }

    @Test
    void malformedBearerTokenCannotAuthenticateLogout() throws Exception {
        when(tokenProvider.validateAccessToken("malformed-token")).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer malformed-token"))
                .andExpect(status().isUnauthorized());

        verify(tokenProvider).validateAccessToken("malformed-token");
        verify(tokenProvider, never()).validateToken("malformed-token");
        verifyNoInteractions(authService);
    }

    @Test
    void verifiedBearerSubjectIsTheOnlyLogoutIdentitySource() throws Exception {
        when(tokenProvider.validateAccessToken("access-token")).thenReturn(true);
        when(tokenProvider.getAccountIdFromToken("access-token")).thenReturn(ACCOUNT_ID);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer access-token")
                        .header("X-User-Id", "spoofed-user"))
                .andExpect(status().isNoContent());

        verify(authService).logout(ACCOUNT_ID, "access-token");
    }

    @Test
    void validRefreshBearerTokenRemainsUnauthenticated() throws Exception {
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
                TOKEN_SECRET,
                Duration.ofMinutes(15).toMillis(),
                Duration.ofDays(14).toMillis(),
                Clock.systemUTC());
        String refreshToken = jwtTokenProvider.generateRefreshToken(ACCOUNT_ID);
        assertThat(jwtTokenProvider.validateToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.validateAccessToken(refreshToken)).isFalse();
        when(tokenProvider.validateAccessToken(refreshToken)).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(tokenProvider).validateAccessToken(refreshToken);
        verify(tokenProvider, never()).validateToken(refreshToken);
        verify(tokenProvider, never()).getAccountIdFromToken(refreshToken);
        verifyNoInteractions(authService);
    }

    @Test
    void clientSuppliedUserHeaderCannotAuthenticateLogout() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-User-Id", ACCOUNT_ID.toString())
                        .header("Authorization", "Basic client-data"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(IdentityHttpConfiguration.class)
    static class TestConfiguration {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        OAuthAuthorizationService oauthAuthorizationService() {
            return mock(OAuthAuthorizationService.class);
        }

        @Bean
        OAuthLoginService oauthLoginService() {
            return mock(OAuthLoginService.class);
        }

        @Bean
        TokenProviderPort tokenProvider() {
            return mock(TokenProviderPort.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
