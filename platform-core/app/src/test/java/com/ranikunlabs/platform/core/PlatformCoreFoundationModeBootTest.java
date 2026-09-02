package com.ranikunlabs.platform.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the executable host still boots in foundation mode.
 *
 * <p>With the Identity Auth runtime disabled the host must start without a JWT signing secret, a
 * Carelog claims base URL, a Product claims service token, or any Kakao credential, so enabling
 * Identity Auth stays an explicit deployment decision rather than a new hard requirement on every
 * environment that hosts Platform Core.
 */
class PlatformCoreFoundationModeBootTest {

    @Test
    void bootsWithoutAnyAuthOrProductClaimsSecretWhenIdentityAuthIsDisabled() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PlatformCoreApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--PLATFORM_CORE_PORT=0",
                        "--platform.identity.auth.enabled=false",
                        "--platform.identity.persistence.enabled=false",
                        "--platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "--platform.identity.datasource.username=identity",
                        "--platform.identity.datasource.password=provided-at-runtime",
                        "--platform.identity.redis-host=redis",
                        "--platform.identity.redis-port=6379",
                        "--platform.identity.redis-key-prefix=platform:identity:",
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")) {

            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().containsProperty("jwt.secret-key")).isFalse();
            assertThat(context.getEnvironment()
                    .containsProperty("platform.identity.product-claims.base-url")).isFalse();
            assertThat(context.getEnvironment()
                    .containsProperty("platform.identity.product-claims.service-token")).isFalse();
        }
    }
}
