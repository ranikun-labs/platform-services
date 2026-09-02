package com.ranikunlabs.platform.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Host-wide HTTP security policy for Platform Core, outside the Identity Auth namespace.
 *
 * <p>Identity contributes a chain scoped to {@code /api/v1/auth/**}, which orders ahead of this one
 * and keeps sole ownership of that namespace: the exact public Auth POST endpoints stay public and
 * logout stays authenticated. This chain states what the host does with everything else, so that
 * behaviour is a deliberate decision rather than a side effect of Spring Boot's default chain
 * backing off once any {@code SecurityFilterChain} bean exists.
 *
 * <p>The policy preserves the host's approved current behaviour. Platform Core's only other hosted
 * HTTP surfaces are the actuator health/info endpoints and the Spring error dispatch; Shared AI
 * publishes no production HTTP route at all — its internal execution route exists solely in the
 * integration-test boundary. None of those are authenticated today, and this slice does not
 * introduce authentication for them. Sessions are stateless and CSRF is disabled because the host
 * serves token-based APIs and holds no cookie-backed session.
 *
 * <p>Consequently a new non-Identity endpoint added to this host is reachable without
 * authentication until it is given a rule here. Authorization for such a surface is the owning
 * module's decision and must be stated explicitly when that surface is introduced.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformCoreSecurityConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    SecurityFilterChain platformCoreHostSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
