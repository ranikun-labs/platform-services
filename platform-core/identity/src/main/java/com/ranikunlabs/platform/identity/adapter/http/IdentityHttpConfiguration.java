package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Servlet-host composition for the Identity Auth/OAuth HTTP adapters. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@Import({AuthController.class, OAuthHttpResponseMapper.class, IdentityHttpExceptionHandler.class})
public class IdentityHttpConfiguration {

    private static final String AUTH_BASE_PATH = "/api/v1/auth";

    @Bean
    IdentityBearerAuthenticationFilter identityBearerAuthenticationFilter(TokenProviderPort tokenProvider) {
        return new IdentityBearerAuthenticationFilter(tokenProvider);
    }

    /**
     * The bearer filter is owned by the Identity {@link SecurityFilterChain}; it must not also
     * be registered as an independent servlet filter by Spring Boot.
     */
    @Bean
    FilterRegistrationBean<IdentityBearerAuthenticationFilter> identityBearerAuthenticationFilterRegistration(
            IdentityBearerAuthenticationFilter authenticationFilter
    ) {
        FilterRegistrationBean<IdentityBearerAuthenticationFilter> registration =
                new FilterRegistrationBean<>(authenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    IdentityAuthenticationEntryPoint identityAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        return new IdentityAuthenticationEntryPoint(objectMapper, clock);
    }

    @Bean
    IdentityAccessDeniedHandler identityAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        return new IdentityAccessDeniedHandler(objectMapper, clock);
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            IdentityBearerAuthenticationFilter bearerAuthenticationFilter,
            IdentityAuthenticationEntryPoint authenticationEntryPoint,
            IdentityAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .securityMatcher(AUTH_BASE_PATH + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.POST,
                                AUTH_BASE_PATH + "/login",
                                AUTH_BASE_PATH + "/refresh",
                                AUTH_BASE_PATH + "/oauth/kakao/authorization",
                                AUTH_BASE_PATH + "/oauth/kakao/exchange"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(bearerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
