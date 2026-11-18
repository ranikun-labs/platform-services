package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityModuleConfiguration;
import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class IdentityFlywayDatasourceConfigurationTest {

    private static final String RUNTIME_USERNAME = "runtime-user-sentinel";
    private static final String RUNTIME_PASSWORD = "runtime-password-sentinel";
    private static final String MIGRATOR_USERNAME = "migrator-user-sentinel";
    private static final String MIGRATOR_PASSWORD = "migrator-password-sentinel";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "local"})
    void nonProductionWithoutMigratorCredentialsKeepsRuntimeFlywayDatasource(String environment) {
        DriverManagerDataSource runtimeDataSource = runtimeDataSource();
        FluentConfiguration flyway = Flyway.configure().dataSource(runtimeDataSource);

        flywayCustomizer(null, environment).customize(flyway);

        assertThat(flyway.getDataSource()).isSameAs(runtimeDataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void productionWithoutAnyMigratorCredentialsFailsBeforeRuntimeDatasourceCanBeUsed(String password) {
        DriverManagerDataSource runtimeDataSource = runtimeDataSource();
        FluentConfiguration flyway = Flyway.configure().dataSource(runtimeDataSource);
        IdentityRuntimeProperties.FlywayDatasource noMigrator =
                new IdentityRuntimeProperties.FlywayDatasource(null, null, password);

        assertThatThrownBy(() -> flywayCustomizer(noMigrator, "prod").customize(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identity Flyway datasource URL, username, and password are required in production")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(RUNTIME_USERNAME, RUNTIME_PASSWORD, MIGRATOR_USERNAME, MIGRATOR_PASSWORD));
        assertThat(flyway.getDataSource()).isSameAs(runtimeDataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void productionSpringProfileOverridesNonProductionMetricsMarker(String metricsEnvironment) {
        assertProductionConfigurationFailsWithoutRuntimeFallback(
                new IdentityRuntimeProperties.FlywayDatasource(null, null, null),
                metricsEnvironment,
                "prod");
    }

    @Test
    void productionSpringProfileFailsWhenMetricsMarkerIsUnset() {
        assertProductionConfigurationFailsWithoutRuntimeFallback(
                new IdentityRuntimeProperties.FlywayDatasource(null, null, null),
                null,
                "prod");
    }

    @Test
    void productionNamedSpringProfileOverridesNonProductionMetricsMarker() {
        assertProductionConfigurationFailsWithoutRuntimeFallback(
                new IdentityRuntimeProperties.FlywayDatasource(null, null, null),
                "local",
                "production");
    }

    @Test
    void productionSpringContextFailsBeforeRuntimeFlywayFallbackWhenMigratorIsMissing() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();

            Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("prod")
                    .properties(
                            "spring.main.web-application-type=none",
                            "platform.identity.persistence.enabled=true",
                            "platform.identity.auth.enabled=false",
                            "platform.identity.datasource.url=" + postgres.getJdbcUrl(),
                            "platform.identity.datasource.username=" + postgres.getUsername(),
                            "platform.identity.datasource.password=" + postgres.getPassword(),
                            "platform.identity.redis-host=localhost",
                            "platform.identity.redis-port=6379",
                            "platform.identity.redis-key-prefix=test:identity:",
                            "PLATFORM_ENVIRONMENT=local",
                            "platform.metrics.environment=local")
                    .run());

            assertThat(failure).isNotNull();
            assertThat(findIllegalStateException(failure))
                    .hasMessage("Identity Flyway datasource URL, username, and password are required in production");
            assertThat(failure.toString()).doesNotContain(postgres.getPassword());
            assertThat(historyTableExists(postgres)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    void rawProductionEnvironmentCannotBeMaskedByLocalMetricsMarker(String rawEnvironment) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("PLATFORM_ENVIRONMENT", rawEnvironment)
                .withProperty("platform.metrics.environment", "local");

        assertProductionConfigurationFailsWithoutRuntimeFallback(
                new IdentityRuntimeProperties.FlywayDatasource(null, null, null),
                environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    void effectiveProductionEnvironmentMarkerRequiresMigratorCredentials(String metricsEnvironment) {
        assertProductionConfigurationFailsWithoutRuntimeFallback(
                new IdentityRuntimeProperties.FlywayDatasource(null, null, null),
                metricsEnvironment);
    }

    @ParameterizedTest
    @MethodSource("partialMigratorCredentials")
    void productionWithPartialMigratorCredentialsFailsBeforeRuntimeDatasourceCanBeUsed(
            String url,
            String username,
            String password) {
        DriverManagerDataSource runtimeDataSource = runtimeDataSource();
        FluentConfiguration flyway = Flyway.configure().dataSource(runtimeDataSource);
        IdentityRuntimeProperties.FlywayDatasource partialMigrator =
                new IdentityRuntimeProperties.FlywayDatasource(url, username, password);

        assertThatThrownBy(() -> flywayCustomizer(partialMigrator, "prod").customize(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identity Flyway datasource requires URL, username, and password together")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(RUNTIME_USERNAME, RUNTIME_PASSWORD, MIGRATOR_USERNAME, MIGRATOR_PASSWORD));
        assertThat(flyway.getDataSource()).isSameAs(runtimeDataSource);
    }

    private static Stream<Arguments> partialMigratorCredentials() {
        return Stream.of(
                arguments("jdbc:postgresql://migrator.invalid/platform", MIGRATOR_USERNAME, null),
                arguments("jdbc:postgresql://migrator.invalid/platform", null, MIGRATOR_PASSWORD),
                arguments(null, MIGRATOR_USERNAME, MIGRATOR_PASSWORD),
                arguments("jdbc:postgresql://migrator.invalid/platform", null, null),
                arguments(null, MIGRATOR_USERNAME, null),
                arguments(null, null, MIGRATOR_PASSWORD));
    }

    private static FlywayConfigurationCustomizer flywayCustomizer(
            IdentityRuntimeProperties.FlywayDatasource flywayDatasource,
            String environment) {
        return flywayCustomizer(flywayDatasource, environment, new String[0]);
    }

    private static FlywayConfigurationCustomizer flywayCustomizer(
            IdentityRuntimeProperties.FlywayDatasource flywayDatasource,
            String environment,
            String... activeProfiles) {
        MockEnvironment springEnvironment = new MockEnvironment();
        if (environment != null) {
            springEnvironment.setProperty("platform.metrics.environment", environment);
        }
        springEnvironment.setActiveProfiles(activeProfiles);
        return new IdentityPersistenceConfiguration().identityFlywayLocation(
                identityProperties(flywayDatasource), springEnvironment);
    }

    private static void assertProductionConfigurationFailsWithoutRuntimeFallback(
            IdentityRuntimeProperties.FlywayDatasource migrationDatasource,
            String metricsEnvironment,
            String... activeProfiles) {
        MockEnvironment springEnvironment = new MockEnvironment();
        if (metricsEnvironment != null) {
            springEnvironment.setProperty("platform.metrics.environment", metricsEnvironment);
        }
        springEnvironment.setActiveProfiles(activeProfiles);
        assertProductionConfigurationFailsWithoutRuntimeFallback(migrationDatasource, springEnvironment);
    }

    private static void assertProductionConfigurationFailsWithoutRuntimeFallback(
            IdentityRuntimeProperties.FlywayDatasource migrationDatasource,
            MockEnvironment springEnvironment) {
        DriverManagerDataSource runtimeDataSource = runtimeDataSource();
        FluentConfiguration flyway = Flyway.configure().dataSource(runtimeDataSource);

        assertThatThrownBy(() -> new IdentityPersistenceConfiguration().identityFlywayLocation(
                identityProperties(migrationDatasource), springEnvironment).customize(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identity Flyway datasource URL, username, and password are required in production")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(RUNTIME_USERNAME, RUNTIME_PASSWORD, MIGRATOR_USERNAME, MIGRATOR_PASSWORD));
        assertThat(flyway.getDataSource()).isSameAs(runtimeDataSource);
    }

    private static IdentityRuntimeProperties identityProperties(
            IdentityRuntimeProperties.FlywayDatasource flywayDatasource) {
        return new IdentityRuntimeProperties(
                new IdentityRuntimeProperties.Datasource(
                        "jdbc:postgresql://runtime.invalid/platform", RUNTIME_USERNAME, RUNTIME_PASSWORD),
                flywayDatasource,
                "localhost",
                6379,
                "identity:");
    }

    private static DriverManagerDataSource runtimeDataSource() {
        return new DriverManagerDataSource(
                "jdbc:postgresql://runtime.invalid/platform", RUNTIME_USERNAME, RUNTIME_PASSWORD);
    }

    private static IllegalStateException findIllegalStateException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException illegalStateException) {
                return illegalStateException;
            }
        }
        return null;
    }

    private static boolean historyTableExists(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, "identity");
            statement.setString(2, "flyway_schema_history");
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(IdentityModuleConfiguration.class)
    static class TestApplication {
    }
}
