package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Registers the production Kakao provider only when it is actually configured.
 *
 * <p>Composition is gated on {@code oauth.kakao.client-id}. With no Kakao configuration the provider
 * simply does not exist — foundation mode boots, and {@code OAuthProviderRegistry} reports Kakao as
 * an unsupported provider rather than pretending a stub is the real integration. With a client id
 * present, {@link KakaoOAuthProperties} validation makes any incomplete or unusable Kakao
 * configuration a startup failure instead of a runtime surprise.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "oauth.kakao", name = "client-id")
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthConfiguration {

    @Bean
    OAuthProviderPort kakaoOAuthProviderAdapter(KakaoOAuthProperties properties, Clock clock) {
        return new KakaoOAuthProviderAdapter(
                new KakaoOAuthApiClient(kakaoRestClient(properties), properties), properties, clock);
    }

    /** A Kakao-only client so its timeouts never influence other outbound Identity calls. */
    private RestClient kakaoRestClient(KakaoOAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
