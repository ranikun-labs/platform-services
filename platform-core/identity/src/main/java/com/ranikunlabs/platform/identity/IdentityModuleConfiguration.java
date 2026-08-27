package com.ranikunlabs.platform.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityRuntimeProperties.class)
public class IdentityModuleConfiguration {
}
