package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

/**
 * Kakao user response reduced to the service user identifier.
 *
 * <p>Email, nickname, and profile fields are intentionally not modelled: the identity key is the
 * Kakao id alone, and unparsed personal data cannot leak through this adapter.
 */
record KakaoUserResponse(Long id) {
}
