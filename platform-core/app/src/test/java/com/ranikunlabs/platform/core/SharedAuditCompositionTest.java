package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SharedAuditCompositionTest {

    private ApplicationContextRunner contextRunner(boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PlatformCoreApplication.class)
                .withPropertyValues(
                        "platform.identity.persistence.enabled=false",
                        "platform.identity.auth.enabled=false",
                        "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "platform.identity.datasource.username=identity",
                        "platform.identity.datasource.password=provided-at-runtime",
                        "platform.identity.redis-host=redis",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=platform:identity:",
                        "carelog.auth.oauth.kakao.client-id=test-only-kakao-client",
                        "carelog.auth.oauth.kakao.client-secret=test-only-kakao-secret",
                        "carelog.auth.oauth.kakao.authorization-uri=https://kauth.example.test/oauth/authorize",
                        "carelog.auth.oauth.kakao.token-uri=https://kapi.example.test/oauth/token",
                        "carelog.auth.oauth.kakao.user-info-uri=https://kapi.example.test/v2/user/me",
                        "jwt.secret-key=01234567890123456789012345678901",
                        "jwt.access-token-validity=900000",
                        "jwt.refresh-token-validity=1209600000",
                        "platform.shared-ai.foundation.enabled=true",
                        "platform.shared-ai.spring-ai.enabled=false",
                        "platform.shared-audit.enabled=" + enabled,
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
    }

    @Test
    void disabledAppCompositionHasNoActiveSharedAuditPublisher() {
        contextRunner(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditPublisher.class);
        });
    }

    @Test
    void enabledAppCompositionExposesOnlyTheNeutralPublisherBoundary() {
        contextRunner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuditPublisher.class);
        });
    }
}
