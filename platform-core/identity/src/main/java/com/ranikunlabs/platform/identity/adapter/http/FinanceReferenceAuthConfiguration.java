package com.ranikunlabs.platform.identity.adapter.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class FinanceReferenceAuthConfiguration {

    private static final int MAX_EMAIL_BYTES = 254;

    @Bean
    @Order(0)
    SecurityFilterChain financeReferenceSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher(request -> HttpMethod.POST.matches(request.getMethod())
                        && (FinanceReferenceAuthController.PATH.equals(request.getServletPath())
                        || FinanceReferenceSignupController.PATH.equals(request.getServletPath())))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new FinanceReferenceRequestBodyLimitFilter(objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new FinanceReferenceRequestBodyLimitFilter(objectMapper,
                                FinanceReferenceSignupController.PATH, "email", MAX_EMAIL_BYTES),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
