package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtProperties;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FinanceReferenceConfigurationGuardTest {

    private static final String ACCOUNT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String PUBLIC_TEST_KEY = "Finance-Reference-Test-Only-Public-Dummy-Key-2026";

    @Test
    void acceptsOnlyCompleteFixtureProfileConfigurationAndIgnoresAbsentOptionalMarkers() {
        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)),
                properties(),
                false,
                new MockEnvironment().withProperty("spring.profiles.active", "finance-reference")))
                .containsExactly(java.util.UUID.fromString(ACCOUNT_ID));
    }

    @Test
    void rejectsTrimmedProductionEnvironmentMarker() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("PLATFORM_ENVIRONMENT", " prod ");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), false, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production marker");
    }

    @Test
    void enabledReferenceModeCannotStartWithoutItsExplicitProfile() {
        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)),
                properties(),
                false,
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit profile");
    }

    @Test
    void enabledConfigurationReachesGuardAndFailsStartupWithoutProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(FinanceReferenceIdentityConfiguration.class, GuardContextBeans.class)
                .withPropertyValues(
                        "platform.identity.finance-reference.enabled=true",
                        "platform.identity.finance-reference.selected-account-ids[0]=" + ACCOUNT_ID,
                        "platform.identity.finance-reference.jwt.signing-key=" + PUBLIC_TEST_KEY,
                        "platform.identity.finance-reference.jwt.issuer=" + FinanceReferenceConfigurationGuard.ISSUER,
                        "platform.identity.finance-reference.jwt.audience=" + FinanceReferenceConfigurationGuard.AUDIENCE,
                        "platform.identity.finance-reference.jwt.access-token-ttl-seconds=300")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Finance identity reference requires its explicit profile.");
                });
    }

    @Test
    void acceptsFinanceReferenceAlongsideCarelogAuthOnlyWithDevDualAuthOptIn() {
        MockEnvironment environment = devDualAuthEnvironment()
                .withProperty("platform.identity.auth.enabled", "true");

        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)),
                properties(),
                false,
                environment))
                .containsExactly(java.util.UUID.fromString(ACCOUNT_ID));
    }

    @Test
    void carelogAuthWithoutOptInKeepsOriginalSharedCompositionRejection() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), false, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Finance identity reference cannot share the Carelog auth composition.");
    }

    @Test
    void carelogAuthWithFlagButWithoutDevProfileIsRejectedWithClearMessage() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("platform.identity.auth.enabled", "true")
                .withProperty("platform.identity.finance-reference.dev-dual-auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), false, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the configured dev deployment environment");
    }

    @Test
    void flagWithoutDevProfileIsRejectedEvenWithoutCarelogAuth() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("platform.identity.finance-reference.dev-dual-auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), false, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the configured dev deployment environment");
    }

    @Test
    void prodProfileRejectsEvenWithDevDualAuthFlag() {
        MockEnvironment environment = devDualAuthEnvironment()
                .withProperty("spring.profiles.active", "finance-reference,dev,prod")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), true, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production marker");
    }

    @Test
    void productionEnvironmentMarkerRejectsEvenWithDevDualAuthFlag() {
        MockEnvironment environment = devDualAuthEnvironment()
                .withProperty("PLATFORM_ENVIRONMENT", "prod")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), true, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production marker");
    }

    @Test
    void financeOnlyZeroSelectedAccountsAcceptedUnderSignupAndDevOptInWithoutCarelogAuth() {
        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of()),
                properties(),
                true,
                devDualAuthEnvironment()))
                .isEmpty();
        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, null),
                properties(),
                true,
                devDualAuthEnvironment()))
                .isEmpty();
    }

    @Test
    void rejectsZeroSelectedAccountsWhenSignupEnabledWithoutOptIn() {
        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of()),
                properties(),
                true,
                new MockEnvironment().withProperty("spring.profiles.active", "finance-reference")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured dev deployment environment");
    }

    @Test
    void acceptsZeroSelectedAccountsWhenSignupDisabledWithDevDualAuthOptIn() {
        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of()), properties(), false, devDualAuthEnvironment()))
                .isEmpty();
    }

    @Test
    void rejectsMoreThanSixteenSelectedAccountsEvenWithSignupAndOptIn() {
        List<String> seventeen = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> java.util.UUID.randomUUID().toString())
                .toList();

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, seventeen),
                properties(),
                true,
                devDualAuthEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Finance identity reference requires 1–16 selected account IDs.");
    }

    @Test
    void rejectsMoreThanSixteenSelectedAccountsWithoutSignupOrOptIn() {
        List<String> seventeen = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> java.util.UUID.randomUUID().toString())
                .toList();

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, seventeen),
                properties(),
                false,
                new MockEnvironment().withProperty("spring.profiles.active", "finance-reference")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Finance identity reference requires 1–16 selected account IDs.");
    }

    @Test
    void configuredDevEnvironmentActivatesDualAuthWithoutDevProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("platform.environment", "DEV")
                .withProperty("platform.identity.finance-reference.dev-dual-auth.enabled", "true")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThat(FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), false, environment))
                .containsExactly(java.util.UUID.fromString(ACCOUNT_ID));
    }

    @Test
    void platformEnvironmentPropertyProdRejectsEvenWithFlag() {
        MockEnvironment environment = devDualAuthEnvironment()
                .withProperty("platform.environment", "prod")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), true, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production marker");
    }

    @Test
    void metricsEnvironmentProductionRejectsEvenWithFlag() {
        MockEnvironment environment = devDualAuthEnvironment()
                .withProperty("platform.metrics.environment", "production")
                .withProperty("platform.identity.auth.enabled", "true");

        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)), properties(), true, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production marker");
    }

    private static MockEnvironment devDualAuthEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.profiles.active", "finance-reference")
                .withProperty("PLATFORM_ENVIRONMENT", "dev")
                .withProperty("platform.identity.finance-reference.dev-dual-auth.enabled", "true");
    }

    private static FinanceReferenceJwtProperties properties() {
        return new FinanceReferenceJwtProperties(
                PUBLIC_TEST_KEY,
                FinanceReferenceConfigurationGuard.ISSUER,
                FinanceReferenceConfigurationGuard.AUDIENCE,
                FinanceReferenceConfigurationGuard.ACCESS_TTL_SECONDS);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GuardContextBeans {
        @Bean
        PasswordCredentialJpaRepository passwordCredentialJpaRepository() {
            return mock(PasswordCredentialJpaRepository.class);
        }

        @Bean
        AccountStatusPort accountStatusPort() {
            return mock(AccountStatusPort.class);
        }

        /** Stands in for the shared encoder IdentityModuleConfiguration provides in production. */
        @Bean
        PasswordEncoder identityPasswordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }
}
