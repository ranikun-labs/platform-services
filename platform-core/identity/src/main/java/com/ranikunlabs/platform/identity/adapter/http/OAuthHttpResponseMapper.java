package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import com.ranikunlabs.platform.identity.adapter.http.dto.response.KakaoExchangeResponse;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Translates provider-neutral OAuth results to the canonical Kakao HTTP response. */
public class OAuthHttpResponseMapper {

    private static final String ACCOUNT_NOT_LINKED = "연결된 Carelog 계정이 없습니다.";
    private static final String IDENTITY_CONFLICT = "OAuth 계정 연결 상태가 유효하지 않습니다.";
    private static final String PROVIDER_AUTH_FAILED = "OAuth 인증에 실패했습니다.";
    private static final String INVALID_STATE = "유효하지 않거나 만료된 인증 요청입니다.";

    public ResponseEntity<ApiResponse<KakaoExchangeResponse>> toKakaoExchangeResponse(
            OAuthLoginResult result
    ) {
        if (result instanceof OAuthLoginResult.ExistingAccountAuthenticated authenticated) {
            AuthTokenBundle tokens = authenticated.tokens();
            return ApiResponse.ok(new KakaoExchangeResponse(tokens.accessToken(), tokens.refreshToken()));
        }
        if (result instanceof OAuthLoginResult.NewAccountOnboardingRequired) {
            return ApiResponse.error(HttpStatus.CONFLICT, ACCOUNT_NOT_LINKED);
        }
        if (result instanceof OAuthLoginResult.ExternalIdentityConflict) {
            return ApiResponse.error(HttpStatus.CONFLICT, IDENTITY_CONFLICT);
        }
        if (result instanceof OAuthLoginResult.ProviderAuthenticationFailed) {
            return ApiResponse.error(HttpStatus.UNAUTHORIZED, PROVIDER_AUTH_FAILED);
        }
        if (result instanceof OAuthLoginResult.InvalidOrExpiredState) {
            return ApiResponse.error(HttpStatus.UNAUTHORIZED, INVALID_STATE);
        }
        throw new IllegalStateException("지원하지 않는 OAuth 로그인 결과입니다.");
    }
}
