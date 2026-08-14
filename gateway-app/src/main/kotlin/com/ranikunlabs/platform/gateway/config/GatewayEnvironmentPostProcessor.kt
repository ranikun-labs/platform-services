package com.ranikunlabs.platform.gateway.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

class GatewayEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        val propertySources = environment.propertySources
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.remove(PROPERTY_SOURCE_NAME)
        }
        val resolved = GatewayEnvironmentAliases.resolve(environment)
        propertySources.addFirst(MapPropertySource(PROPERTY_SOURCE_NAME, resolved))
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    private companion object {
        const val PROPERTY_SOURCE_NAME = "platformGatewayEnvironmentAliases"
    }
}
