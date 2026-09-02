package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderException;

/**
 * Turns Kakao HTTP outcomes into provider-neutral failures without carrying provider detail.
 *
 * <p>A cause is attached only for transport failures, whose messages describe a socket rather than
 * a payload. Status-derived and decoding failures deliberately drop the original exception: those
 * messages can embed the Kakao response body, which may quote the authorization code or a token.
 */
final class KakaoOAuthErrorMapper {

    private KakaoOAuthErrorMapper() {
    }

    /** Rate limiting and provider-side errors are retryable conditions, not credential failures. */
    static OAuthProviderException tokenFailure(int status) {
        return new OAuthProviderException(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
    }

    static OAuthProviderException userInfoFailure(int status) {
        return new OAuthProviderException(status == 429 || status >= 500
                ? OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE
                : OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
    }

    static OAuthProviderException unavailable(Throwable transportFailure) {
        return new OAuthProviderException(
                OAuthLoginResult.FailureReason.PROVIDER_UNAVAILABLE, transportFailure);
    }

    static OAuthProviderException codeExchangeFailed() {
        return new OAuthProviderException(OAuthLoginResult.FailureReason.CODE_EXCHANGE_FAILED);
    }

    static OAuthProviderException principalUnverified() {
        return new OAuthProviderException(OAuthLoginResult.FailureReason.PRINCIPAL_UNVERIFIED);
    }
}
