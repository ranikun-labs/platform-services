package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.request.KakaoAuthorizationRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.KakaoExchangeRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.LoginRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.TokenRefreshRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.KakaoAuthorizationResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.KakaoExchangeResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.LoginResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.TokenRefreshResponse;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Carelog-compatible Auth/OAuth HTTP boundary for Identity.
 *
 * <p>The wire paths reproduce the canonical {@code /api/v1} context plus the {@code /auth}
 * mapping as one module-owned path so the contract does not depend on host context-path
 * configuration. This adapter only translates transport shapes; all behavior stays in
 * {@link AuthService}, {@link OAuthAuthorizationService}, and {@link OAuthLoginService}.
 */
@RestController
@RequestMapping(IdentityAuthController.BASE_PATH)
public class IdentityAuthController {

    static final String BASE_PATH = "/api/v1/auth";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final OAuthAuthorizationService oAuthAuthorizationService;
    private final OAuthLoginService oAuthLoginService;

    public IdentityAuthController(
            AuthService authService,
            OAuthAuthorizationService oAuthAuthorizationService,
            OAuthLoginService oAuthLoginService
    ) {
        this.authService = authService;
        this.oAuthAuthorizationService = oAuthAuthorizationService;
        this.oAuthLoginService = oAuthLoginService;
    }

    @PostMapping("/login")
    public ResponseEntity<IdentityApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        AuthTokenBundle tokens = authService.login(request.userId(), request.password());
        return IdentityApiResponse.ok(new LoginResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<IdentityApiResponse<TokenRefreshResponse>> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        AuthTokenBundle tokens = authService.refreshToken(request.refreshToken());
        return IdentityApiResponse.ok(new TokenRefreshResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        // authentication.getName() is the Platform account UUID string established by the
        // Identity bearer authentication filter.
        UUID accountId = UUID.fromString(authentication.getName());
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            return IdentityApiResponse.noContent();
        }
        String accessToken = authorizationHeader.substring(BEARER_PREFIX.length());
        authService.logout(accountId, accessToken);
        return IdentityApiResponse.noContent();
    }

    @PostMapping("/oauth/kakao/authorization")
    public ResponseEntity<IdentityApiResponse<KakaoAuthorizationResponse>> startKakaoAuthorization(
            @Valid @RequestBody KakaoAuthorizationRequest request
    ) {
        OAuthAuthorizationService.AuthorizationUrlResult result =
                oAuthAuthorizationService.startAuthorization(new OAuthAuthorizationCommand(
                        "kakao",
                        request.clientChannel(),
                        request.returnTo()
                ));
        // Only the authorization URL is exposed; the server-side state stays in the state store.
        return IdentityApiResponse.ok(
                new KakaoAuthorizationResponse(result.authorizationUrl().toString()));
    }

    @PostMapping("/oauth/kakao/exchange")
    public ResponseEntity<IdentityApiResponse<KakaoExchangeResponse>> exchangeKakaoCode(
            @Valid @RequestBody KakaoExchangeRequest request
    ) {
        OAuthLoginResult result = oAuthLoginService.completeLogin(new OAuthCallbackCommand(
                "kakao",
                request.code(),
                request.state()
        ));
        return IdentityApiResponse.ok(toKakaoExchangeResponse(result));
    }

    private KakaoExchangeResponse toKakaoExchangeResponse(OAuthLoginResult result) {
        return switch (result) {
            case OAuthLoginResult.ExistingAccountAuthenticated authenticated -> new KakaoExchangeResponse(
                    authenticated.tokens().accessToken(),
                    authenticated.tokens().refreshToken());
            // Linking an OAuth identity to an account is a separate onboarding decision; the
            // exchange endpoint never creates or links accounts on its own.
            case OAuthLoginResult.NewAccountOnboardingRequired ignored ->
                    throw new IdentityHttpException(IdentityErrorStatus.OAUTH_ACCOUNT_NOT_LINKED);
            case OAuthLoginResult.ExternalIdentityConflict ignored ->
                    throw new IdentityHttpException(IdentityErrorStatus.OAUTH_IDENTITY_CONFLICT);
            case OAuthLoginResult.ProviderAuthenticationFailed ignored ->
                    throw new IdentityHttpException(IdentityErrorStatus.OAUTH_PROVIDER_AUTH_FAILED);
            case OAuthLoginResult.InvalidOrExpiredState ignored ->
                    throw new IdentityHttpException(IdentityErrorStatus.INVALID_OAUTH_STATE);
        };
    }
}
