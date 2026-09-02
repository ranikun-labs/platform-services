package com.ranikunlabs.platform.identity.adapter.productclaims;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Composes the production Product claims dependency.
 *
 * <p>Gated on a configured base URL so a host that supplies its own {@link CRMIdentityProjectionPort}
 * is unaffected. When Identity Auth is enabled and no base URL is configured, this adapter is absent
 * and the authentication use cases cannot be constructed, so the host fails to start rather than
 * running without a Product claims source.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.identity.product-claims", name = "base-url")
@EnableConfigurationProperties(CarelogProductClaimsProperties.class)
public class IdentityProductClaimsConfiguration {

    @Bean
    RestClient identityProductClaimsRestClient(CarelogProductClaimsProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUri().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    CRMIdentityProjectionPort carelogProductClaimsHttpAdapter(
            RestClient identityProductClaimsRestClient,
            CarelogProductClaimsProperties properties
    ) {
        return new CarelogProductClaimsHttpAdapter(
                identityProductClaimsRestClient, properties.serviceToken());
    }
}
