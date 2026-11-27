package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.CarelogRegistrationStore;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.CarelogEnrollment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CarelogRegistrationCompositionConfigurationTest {
    private final DataSource source = mock(DataSource.class);
    private final JpaTransactionManager manager = mock(JpaTransactionManager.class);
    private ApplicationContextRunner context() {
        when(manager.getDataSource()).thenReturn(source);
        return new ApplicationContextRunner()
                .withUserConfiguration(CarelogRegistrationCompositionConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(JpaTransactionManager.class, () -> manager)
                .withBean(PlatformAccountJpaRepository.class, () -> mock(PlatformAccountJpaRepository.class))
                .withBean(PasswordCredentialJpaRepository.class, () -> mock(PasswordCredentialJpaRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
    }
    private ApplicationContextRunner enabled() {
        return context().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("platform.identity.carelog-registration.enabled=true",
                        "platform.identity.auth.enabled=true", "platform.identity.persistence.enabled=true",
                        "platform.identity.carelog-enrollment.enabled=true",
                        "platform.identity.carelog-enrollment.base-url=https://carelog.example.test",
                        "platform.identity.carelog-enrollment.credential=01234567890123456789012345678901");
    }
    @Test void registrationOffLeavesNoStoreCoordinatorOrEnrollment() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(CarelogRegistrationStore.class);
            assertThat(ctx).doesNotHaveBean(CarelogRegistrationCoordinator.class);
            assertThat(ctx).doesNotHaveBean(CarelogEnrollment.class);
        });
    }
    @Test void devOptInBuildsRealTransportAndStoreGraph() {
        enabled().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(CarelogRegistrationStore.class);
            assertThat(ctx).hasSingleBean(CarelogRegistrationCoordinator.class);
            assertThat(ctx).hasSingleBean(CarelogEnrollment.class);
            assertThat(ctx.getBean(CarelogEnrollment.class).getClass().getSimpleName()).isEqualTo("CarelogEnrollmentHttpAdapter");
        });
    }
    @Test void explicitDevDeploymentBuildsGraphWithoutDevProfile() {
        enabled().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("finance-reference"))
                .withPropertyValues("PLATFORM_ENVIRONMENT=dev")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CarelogRegistrationCoordinator.class);
                });
        enabled().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("finance-reference"))
                .withPropertyValues("platform.environment=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
    @Test void contradictoryOrProductionMarkersFailClosedEvenWithDevDeployment() {
        enabled().withPropertyValues("PLATFORM_ENVIRONMENT=dev", "platform.environment=staging")
                .run(ctx -> assertThat(ctx).hasFailed());
        enabled().withPropertyValues("PLATFORM_ENVIRONMENT=dev", "platform.metrics.environment=production")
                .run(ctx -> assertThat(ctx).hasFailed());
        enabled().withPropertyValues("PLATFORM_ENVIRONMENT=dev")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void missingEnrollmentOrBadOriginFailsClosed() {
        enabled().withPropertyValues("platform.identity.carelog-enrollment.enabled=false").run(ctx -> assertThat(ctx).hasFailed());
        enabled().withPropertyValues("platform.identity.carelog-enrollment.base-url=http://not-loopback.example.test").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void productionAuthDisabledAndMismatchedDatasourceFailClosed() {
        enabled().withPropertyValues("platform.environment=production").run(ctx -> assertThat(ctx).hasFailed());
        enabled().withPropertyValues("platform.identity.auth.enabled=false").run(ctx -> assertThat(ctx).hasFailed());
        enabled().withPropertyValues("platform.identity.persistence.enabled=false").run(ctx -> assertThat(ctx).hasFailed());
        var runner = enabled();
        when(manager.getDataSource()).thenReturn(mock(DataSource.class));
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void nonJpaTransactionManagerIsRejected() {
        var environment = new MockEnvironment()
                .withProperty("platform.identity.auth.enabled", "true")
                .withProperty("platform.identity.persistence.enabled", "true")
                .withProperty("platform.identity.carelog-enrollment.enabled", "true");
        environment.setActiveProfiles("dev");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new CarelogRegistrationCompositionConfiguration().carelogRegistrationStore(environment,
                        mock(PlatformAccountJpaRepository.class), mock(PasswordCredentialJpaRepository.class),
                        mock(PasswordEncoder.class), source, mock(PlatformTransactionManager.class))))
                .isInstanceOf(IllegalStateException.class);
    }
}
