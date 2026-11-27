package com.ranikunlabs.platform.gateway.config

import com.ranikunlabs.platform.gateway.filter.FinanceTrustedContextProfileGuard
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class FinanceDevEnvironmentTest {
    @Test
    fun `only explicit consistent DEV markers activate the deployment lane`() {
        assertThat(isExplicitDevEnvironment(MockEnvironment())).isFalse()
        assertThat(isExplicitDevEnvironment(MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", " DEV "))).isTrue()
        assertThat(isExplicitDevEnvironment(MockEnvironment().withProperty("platform.environment", "dev"))).isTrue()
        assertThat(isExplicitDevEnvironment(MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev")
            .withProperty("platform.environment", "dev"))).isTrue()
        assertThat(isExplicitDevEnvironment(MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev")
            .withProperty("platform.environment", "staging"))).isFalse()
        assertThat(isExplicitDevEnvironment(MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "staging"))).isFalse()
    }

    @Test
    fun `trusted context requires an enabled flag and a valid deployment marker`() {
        val disabled = FinanceTrustedContextConfig()
        FinanceTrustedContextProfileGuard.validate(disabled, MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev"))
        val enabled = FinanceTrustedContextConfig(enabled = true, serviceSecret = "Finance-Dev-Test-Only-Dummy-Secret-Do-Not-Use-2026")
        FinanceTrustedContextProfileGuard.validate(enabled, MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev"))
        for (environment in listOf(
            MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "staging"),
            MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev").withProperty("platform.environment", "staging"),
            MockEnvironment().withProperty("PLATFORM_ENVIRONMENT", "dev").withProperty("platform.metrics.environment", "production")
        )) {
            assertThatThrownBy { FinanceTrustedContextProfileGuard.validate(enabled, environment) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
