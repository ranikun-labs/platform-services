package com.ranikunlabs.platform.gateway.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.mock.env.MockEnvironment

class FinanceRouteConfigurationTest {
    @Test
    fun `enabled Finance modes require an upstream URI`() {
        val configs = listOf(
            GatewayConfig(financeTrustedContext = FinanceTrustedContextConfig(enabled = true)),
            GatewayConfig(financeReference = FinanceReferenceJwtConfig(enabled = true))
        )
        for (config in configs) {
            val configuration = FinanceRouteConfiguration(MockEnvironment(), config)
            assertThatThrownBy { configuration.financeRouteLocator(mock(RouteLocatorBuilder::class.java)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("PLATFORM_GATEWAY_FINANCE_BE_URI")
        }
    }
}
