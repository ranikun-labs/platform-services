package com.ranikunlabs.platform.identity.adapter.enrollment;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.CarelogEnrollment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** Enrollment transport is absent unless explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.identity.carelog-enrollment", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CarelogEnrollmentProperties.class)
public class CarelogEnrollmentClientConfiguration {
    @Bean
    CarelogEnrollment carelogEnrollment(CarelogEnrollmentProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requiredConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requiredReadTimeout());
        RestClient client = RestClient.builder()
                .baseUrl(properties.requiredBaseUri().toString())
                .requestFactory(factory)
                .build();
        return new CarelogEnrollmentHttpAdapter(client, properties.requiredCredential());
    }
}
