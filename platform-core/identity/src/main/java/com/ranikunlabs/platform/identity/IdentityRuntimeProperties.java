package com.ranikunlabs.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.identity")
public record IdentityRuntimeProperties(
        @Valid @NotNull Datasource datasource,
        @NotBlank String redisHost,
        @Min(1) @Max(65535) int redisPort,
        @NotBlank String redisKeyPrefix) {

    public record Datasource(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password) {
    }
}
