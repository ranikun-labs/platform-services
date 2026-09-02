package com.ranikunlabs.platform.identity.adapter.http.security;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Servlet security composition for the Identity Auth namespace only.
 *
 * <p>The chain is scoped with {@code securityMatcher} to {@code /api/v1/auth/**} and deliberately
 * states no policy for any other path. Within the namespace only the exact public POST endpoints
 * are permitted; every other path under it — including endpoints added later — requires
 * authentication.
 *
 * <p>The chain is composed whenever Identity is hosted in a Servlet context with Spring Security
 * present, independently of {@code platform.identity.auth.enabled}. In foundation mode the
 * namespace has no endpoints and the chain simply keeps it closed.
 *
 * <p>Contributing any {@code SecurityFilterChain} bean does influence Spring Boot's security
 * auto-configuration — Boot's default host-wide chain backs off once one exists. That interaction
 * is deliberate here but is not a guarantee this module can make on the host's behalf: host-wide
 * policy is settled by the later Hosted Runtime Composition gate.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SecurityFilterChain.class)
public class IdentityAuthSecurityConfiguration {

    static final String NAMESPACE = "/api/v1/auth/**";
    static final String LOGIN_PATH = "/api/v1/auth/login";
    static final String REFRESH_PATH = "/api/v1/auth/refresh";
    static final String OAUTH_AUTHORIZATION_PATH = "/api/v1/auth/oauth/kakao/authorization";
    static final String OAUTH_EXCHANGE_PATH = "/api/v1/auth/oauth/kakao/exchange";

    @Bean
    IdentityAuthenticationEntryPoint identityAuthenticationEntryPoint(
            ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<Clock> clock
    ) {
        // The failure body is a fixed, Identity-owned shape, so it does not need the host's
        // serialization customizations and must not fail to compose without them.
        return new IdentityAuthenticationEntryPoint(
                objectMapper.getIfAvailable(() -> JsonMapper.builder().build()),
                clock.getIfAvailable(Clock::systemDefaultZone));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    SecurityFilterChain identityAuthSecurityFilterChain(
            HttpSecurity http,
            IdentityAuthenticationEntryPoint authenticationEntryPoint,
            ObjectProvider<TokenProviderPort> tokenProvider
    ) throws Exception {
        http
                .securityMatcher(NAMESPACE)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.POST,
                                LOGIN_PATH,
                                REFRESH_PATH,
                                OAUTH_AUTHORIZATION_PATH,
                                OAUTH_EXCHANGE_PATH
                        ).permitAll()
                        .anyRequest().authenticated());

        TokenProviderPort provider = tokenProvider.getIfAvailable();
        if (provider != null) {
            http.addFilterBefore(
                    new IdentityBearerAuthenticationFilter(provider),
                    UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
