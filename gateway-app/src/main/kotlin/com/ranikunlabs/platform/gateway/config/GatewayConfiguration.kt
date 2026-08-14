package com.ranikunlabs.platform.gateway.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayConfig::class)
class GatewayConfiguration
