package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** Opt-in Carelog Product claims HTTP client and Port composition. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "platform.identity.product-claims",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(CarelogProductClaimsProperties.class)
public class CarelogProductClaimsClientConfiguration {

    @Bean
    RestClient carelogProductClaimsRestClient(CarelogProductClaimsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
                .baseUrl(properties.requiredBaseUri().toString())
                .requestFactory(factory)
                .build();
    }

    @Bean
    CRMIdentityProjectionPort carelogProductClaimsProjectionPort(
            @Qualifier("carelogProductClaimsRestClient") RestClient restClient,
            CarelogProductClaimsProperties properties
    ) {
        return new CarelogProductClaimsHttpAdapter(
                restClient,
                properties.requiredServiceToken());
    }
}
