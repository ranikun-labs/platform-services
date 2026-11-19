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
        @Valid FlywayDatasource flywayDatasource,
        @NotBlank String redisHost,
        @Min(1) @Max(65535) int redisPort,
        @NotBlank String redisKeyPrefix,
        boolean redisTlsEnabled) {

    public record Datasource(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password) {
    }

    /** Optional migration-only credentials; production role and secret delivery remain owner decisions. */
    public record FlywayDatasource(String url, String username, String password) {

        public boolean hasAnyValue() {
            return hasText(url) || hasText(username) || hasText(password);
        }

        public boolean isComplete() {
            return hasText(url) && hasText(username) && hasText(password);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
