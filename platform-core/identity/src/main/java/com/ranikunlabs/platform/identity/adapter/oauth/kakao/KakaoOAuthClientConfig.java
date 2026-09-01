package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** Kakao-only RestClient with bounded connect and read timeouts. */
@Configuration(proxyBeanMethods = false)
@Conditional(KakaoOAuthConfiguredCondition.class)
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthClientConfig {

    @Bean
    RestClient kakaoOAuthRestClient(KakaoOAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    KakaoOAuthErrorMapper kakaoOAuthErrorMapper() {
        return new KakaoOAuthErrorMapper();
    }

    @Bean
    KakaoOAuthApiClient kakaoOAuthApiClient(
            @Qualifier("kakaoOAuthRestClient") RestClient kakaoOAuthRestClient,
            KakaoOAuthProperties properties,
            KakaoOAuthErrorMapper errorMapper) {
        return new KakaoOAuthApiClient(kakaoOAuthRestClient, properties, errorMapper);
    }
}
