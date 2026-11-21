package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CarelogRegistrationHttpConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CarelogRegistrationHttpConfiguration.class);

    @Test void disabledByDefault() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(CarelogRegistrationController.class);
            assertThat(ctx).doesNotHaveBean(CarelogRegistrationEnabled.class);
        });
    }

    @Test void enabledOnlyWithDevProfileAndCoordinator() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("platform.identity.carelog-registration.enabled=true")
                .withBean(CarelogRegistrationCoordinator.class, () -> mock(CarelogRegistrationCoordinator.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CarelogRegistrationController.class);
                    assertThat(ctx).hasSingleBean(CarelogRegistrationEnabled.class);
                });
    }

    @Test void enabledWithoutDevOrWithProductionMarkerFailsClosed() {
        var enabled = context.withPropertyValues("platform.identity.carelog-registration.enabled=true")
                .withBean(CarelogRegistrationCoordinator.class, () -> mock(CarelogRegistrationCoordinator.class));
        enabled.run(ctx -> assertThat(ctx).hasFailed());
        enabled.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("platform.environment=production")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void enabledWithoutCoordinatorFailsClosed() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("platform.identity.carelog-registration.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
