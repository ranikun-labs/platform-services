package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthClientConfig;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
import com.ranikunlabs.platform.identity.adapter.redis.RedisOAuthStateStore;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProductClientCompatibilityResolver;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderCallbackProperties;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderRegistry;
import com.ranikunlabs.platform.identity.application.oauth.OAuthRedirectUriResolver;
import com.ranikunlabs.platform.identity.application.oauth.ReturnToValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Optional OAuth composition for Identity.  Provider and Redis details remain adapters while the
 * application services depend only on the neutral ports.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProviderCallbackProperties.class)
@Import({
        OAuthProviderRegistry.class,
        OAuthProductClientCompatibilityResolver.class,
        OAuthRedirectUriResolver.class,
        ReturnToValidator.class,
        OAuthAuthorizationService.class,
        OAuthLoginService.class,
        RedisOAuthStateStore.class,
        KakaoOAuthClientConfig.class,
        KakaoOAuthProviderAdapter.class
})
public class IdentityOAuthConfiguration {
}
