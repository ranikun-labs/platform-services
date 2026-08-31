package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.request.KakaoAuthorizationRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.KakaoExchangeRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.LoginRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.request.TokenRefreshRequest;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.KakaoAuthorizationResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.KakaoExchangeResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.LoginResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.TokenRefreshResponse;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthCallbackCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Spring MVC adapter for the canonical Carelog Auth/OAuth HTTP contract. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final OAuthAuthorizationService oauthAuthorizationService;
    private final OAuthLoginService oauthLoginService;
    private final OAuthHttpResponseMapper oauthHttpResponseMapper;

    public AuthController(
            AuthService authService,
            OAuthAuthorizationService oauthAuthorizationService,
            OAuthLoginService oauthLoginService,
            OAuthHttpResponseMapper oauthHttpResponseMapper
    ) {
        this.authService = authService;
        this.oauthAuthorizationService = oauthAuthorizationService;
        this.oauthLoginService = oauthLoginService;
        this.oauthHttpResponseMapper = oauthHttpResponseMapper;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        var tokens = authService.login(request.userId(), request.password());
        return ApiResponse.ok(new LoginResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        UUID accountId = UUID.fromString(authentication.getName());
        if (!authHeader.startsWith("Bearer ")) {
            return ApiResponse.noContent();
        }
        authService.logout(accountId, authHeader.substring(7));
        return ApiResponse.noContent();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        var tokens = authService.refreshToken(request.refreshToken());
        return ApiResponse.ok(new TokenRefreshResponse(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/oauth/kakao/authorization")
    public ResponseEntity<ApiResponse<KakaoAuthorizationResponse>> startKakaoAuthorization(
            @Valid @RequestBody KakaoAuthorizationRequest request
    ) {
        OAuthAuthorizationService.AuthorizationUrlResult result =
                oauthAuthorizationService.startAuthorization(
                        new OAuthAuthorizationCommand("kakao", request.clientChannel(), request.returnTo()));
        return ApiResponse.ok(new KakaoAuthorizationResponse(result.authorizationUrl().toString()));
    }

    @PostMapping("/oauth/kakao/exchange")
    public ResponseEntity<ApiResponse<KakaoExchangeResponse>> exchangeKakaoCode(
            @Valid @RequestBody KakaoExchangeRequest request
    ) {
        var result = oauthLoginService.completeLogin(
                new OAuthCallbackCommand("kakao", request.code(), request.state()));
        return oauthHttpResponseMapper.toKakaoExchangeResponse(result);
    }
}
