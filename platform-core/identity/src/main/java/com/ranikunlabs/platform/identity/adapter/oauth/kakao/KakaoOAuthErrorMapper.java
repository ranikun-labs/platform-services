package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;

/** Maps Kakao transport failures to provider-neutral failures without exposing response bodies. */
public class KakaoOAuthErrorMapper {

    public OAuthProviderException tokenFailure(int status) {
        return new OAuthProviderException(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
    }

    public OAuthProviderException userInfoFailure(int status) {
        return new OAuthProviderException(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
    }

    public OAuthProviderException unavailable(Throwable cause) {
        return new OAuthProviderException(OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE, cause);
    }

    public OAuthProviderException codeExchangeFailure(Throwable cause) {
        return new OAuthProviderException(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED, cause);
    }

    public OAuthProviderException principalUnverified(Throwable cause) {
        return new OAuthProviderException(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED, cause);
    }
}
