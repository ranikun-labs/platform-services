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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    // Spring Boot's Jackson auto-configuration is not guaranteed to run for this module in
    // isolation (no starter-web/-json dependency here), so RedisOAuthStateStore's ObjectMapper is
    // provided directly rather than assumed from the host application's auto-configuration.
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper identityOAuthStateObjectMapper() {
        return JsonMapper.builder().build();
    }
}
