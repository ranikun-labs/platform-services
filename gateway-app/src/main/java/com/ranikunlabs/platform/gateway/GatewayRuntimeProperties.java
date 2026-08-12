package com.ranikunlabs.platform.gateway;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.gateway")
public record GatewayRuntimeProperties(
        @NotBlank String redisHost,
        @Min(1) @Max(65535) int redisPort,
        @NotBlank String redisKeyPrefix) {
}
