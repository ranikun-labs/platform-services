package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtProperties;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

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
    void rejectsSharedCarelogAuthComposition() {
        assertThatThrownBy(() -> FinanceReferenceConfigurationGuard.validate(
                new FinanceReferenceProperties(true, List.of(ACCOUNT_ID)),
                properties(),
                true,
                new MockEnvironment().withProperty("spring.profiles.active", "finance-reference")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Carelog auth composition");
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
    }
}
