package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Characterizes the current Flyway startup behavior for one-role fallback and an explicit split-role setup.
 *
 * <p>The same-role test records the current code path only. It is not an Owner decision that production may use
 * one role for both migration and runtime, and it must not be read as an approval of that deployment policy.</p>
 */
class IdentityFlywayRestartCharacterizationIntegrationTest {

    private static final String MIGRATOR_ROLE = "identity_restart_migrator";
    private static final String MIGRATOR_PASSWORD = "identity_restart_migrator_password";
    private static final String RUNTIME_ROLE = "identity_restart_runtime";
    private static final String RUNTIME_PASSWORD = "identity_restart_runtime_password";

    private PostgreSQLContainer<?> postgres;

    @BeforeEach
    void provisionDisposableRolesAndSchema() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR_ROLE + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA identity AUTHORIZATION " + MIGRATOR_ROLE);
        }
    }

    @AfterEach
    void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void sameRoleFallbackCurrentlyCannotRestartFlywayAfterRuntimePrivilegeReconciliation() throws Exception {
        IdentityRuntimeProperties properties = properties(MIGRATOR_ROLE, MIGRATOR_PASSWORD, null, null);
        IdentityPersistenceConfiguration configuration = new IdentityPersistenceConfiguration();
        var migrationStrategy = configuration.identityFlywayMigrationStrategy(properties);

        migrationStrategy.migrate(flyway(properties));

        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isFalse();

        Throwable restartFailure = catchThrowable(() -> migrationStrategy.migrate(flyway(properties)));

        assertThat(restartFailure)
                .as("current same-role fallback characterization; role policy remains an Owner decision")
                .isNotNull()
                .hasStackTraceContaining("flyway_schema_history");
    }

    @Test
    void separateMigratorAndRuntimeRolesCanValidateFlywayHistoryAfterRestart() throws Exception {
        IdentityRuntimeProperties properties = properties(
                RUNTIME_ROLE,
                RUNTIME_PASSWORD,
                MIGRATOR_ROLE,
                MIGRATOR_PASSWORD);
        IdentityPersistenceConfiguration configuration = new IdentityPersistenceConfiguration();
        var migrationStrategy = configuration.identityFlywayMigrationStrategy(properties);

        migrationStrategy.migrate(flyway(properties));

        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isTrue();
        assertThat(hasHistorySelectPrivilege(RUNTIME_ROLE)).isFalse();

        assertThatCode(() -> migrationStrategy.migrate(flyway(properties)))
                .as("restart with explicit Migrator credentials uses the same disposable PostgreSQL database")
                .doesNotThrowAnyException();

        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isTrue();
        assertThat(hasHistorySelectPrivilege(RUNTIME_ROLE)).isFalse();
    }

    private IdentityRuntimeProperties properties(
            String runtimeUsername,
            String runtimePassword,
            String migrationUsername,
            String migrationPassword) {
        IdentityRuntimeProperties.FlywayDatasource migrationDatasource = migrationUsername == null
                ? null
                : new IdentityRuntimeProperties.FlywayDatasource(
                        postgres.getJdbcUrl(), migrationUsername, migrationPassword);
        return new IdentityRuntimeProperties(
                new IdentityRuntimeProperties.Datasource(
                        postgres.getJdbcUrl(), runtimeUsername, runtimePassword),
                migrationDatasource,
                "localhost",
                6379,
                "identity:");
    }

    private Flyway flyway(IdentityRuntimeProperties properties) {
        DataSource runtimeDataSource = new IdentityPersistenceConfiguration().dataSource(properties);
        FluentConfiguration flywayConfiguration = Flyway.configure().dataSource(runtimeDataSource);
        new IdentityPersistenceConfiguration().identityFlywayLocation(properties).customize(flywayConfiguration);
        return flywayConfiguration.load();
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private boolean hasHistorySelectPrivilege(String role) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_table_privilege('" + role + "', 'identity.flyway_schema_history', 'SELECT')")) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }
}
