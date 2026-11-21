package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

        try (FlywaySession firstStartup = flyway(properties)) {
            migrationStrategy.migrate(firstStartup.flyway());
        }

        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isFalse();

        Throwable restartFailure = catchThrowable(() -> {
            try (FlywaySession restarted = flyway(properties)) {
                migrationStrategy.migrate(restarted.flyway());
            }
        });

        assertThat(restartFailure)
                .as("current same-role fallback characterization; role policy remains an Owner decision")
                .isNotNull()
                .hasStackTraceContaining("flyway_schema_history");
        SQLException sqlFailure = findSqlException(restartFailure);
        assertThat((Object) sqlFailure)
                .as("restart is denied by PostgreSQL after the current same-role privilege reconciliation")
                .isNotNull();
        assertThat(sqlFailure.getSQLState()).isEqualTo("42501");
        assertThat(sqlFailure.getMessage()).contains("flyway_schema_history");
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

        try (FlywaySession firstStartup = flyway(properties)) {
            migrationStrategy.migrate(firstStartup.flyway());
        }

        List<FlywayHistoryRow> historyBeforeRestart = flywayHistoryRows();
        assertThat(historyBeforeRestart).extracting(FlywayHistoryRow::version).containsExactly("1", "2", "3");
        assertThat(historyBeforeRestart)
                .extracting(FlywayHistoryRow::description)
                .containsExactly("identity schema", "refresh session single account", "carelog registration operation");
        assertThat(historyBeforeRestart).allSatisfy(row -> {
            assertThat(row.checksum()).as("Flyway should record each accepted migration checksum").isNotNull();
            assertThat(row.success()).isTrue();
        });

        List<ProductClientSeed> productClientsBeforeRestart = productClientRows();
        assertThat(productClientsBeforeRestart).containsExactly(
                new ProductClientSeed("carelog-mobile", "CARELOG", "MOBILE", true),
                new ProductClientSeed("carelog-web", "CARELOG", "WEB", true));

        UUID accountWrittenByRuntime = UUID.randomUUID();
        insertRuntimeAccount(properties, accountWrittenByRuntime);

        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isTrue();
        assertThat(hasHistorySelectPrivilege(RUNTIME_ROLE)).isFalse();
        assertThat(hasRegistrationPrivileges(RUNTIME_ROLE)).isTrue();

        assertThatCode(() -> {
            try (FlywaySession restarted = flyway(properties)) {
                migrationStrategy.migrate(restarted.flyway());
            }
        })
                .as("restart with explicit Migrator credentials uses the same disposable PostgreSQL database")
                .doesNotThrowAnyException();

        List<FlywayHistoryRow> historyAfterRestart = flywayHistoryRows();
        assertThat(historyAfterRestart).containsExactlyElementsOf(historyBeforeRestart);
        assertThat(productClientRows()).containsExactlyElementsOf(productClientsBeforeRestart);
        assertThat(platformAccountStatus(accountWrittenByRuntime)).isEqualTo("ACTIVE");
        assertThat(refreshTokenAccountUniquenessConstraintExists()).isTrue();
        assertThat(hasHistorySelectPrivilege(MIGRATOR_ROLE)).isTrue();
        assertThat(hasHistorySelectPrivilege(RUNTIME_ROLE)).isFalse();
        assertThat(hasRegistrationPrivileges(RUNTIME_ROLE)).isTrue();
    }

    private boolean hasRegistrationPrivileges(String role) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT has_table_privilege(?, 'identity.carelog_registration_operation', 'SELECT') "
                             + "AND has_table_privilege(?, 'identity.carelog_registration_operation', 'INSERT') "
                             + "AND has_table_privilege(?, 'identity.carelog_registration_operation', 'UPDATE')")) {
            statement.setString(1, role);
            statement.setString(2, role);
            statement.setString(3, role);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
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
                "identity:",
                false);
    }

    private FlywaySession flyway(IdentityRuntimeProperties properties) {
        DataSource runtimeDataSource = new IdentityPersistenceConfiguration().dataSource(properties);
        FluentConfiguration flywayConfiguration = Flyway.configure().dataSource(runtimeDataSource);
        new IdentityPersistenceConfiguration().identityFlywayLocation(
                properties,
                new MockEnvironment().withProperty("platform.metrics.environment", "test"))
                .customize(flywayConfiguration);
        return new FlywaySession(
                flywayConfiguration.load(),
                runtimeDataSource,
                flywayConfiguration.getDataSource());
    }

    private SQLException findSqlException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
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

    private List<FlywayHistoryRow> flywayHistoryRows() throws SQLException {
        List<FlywayHistoryRow> rows = new ArrayList<>();
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT installed_rank, version, description, checksum, success "
                             + "FROM identity.flyway_schema_history ORDER BY installed_rank")) {
            while (result.next()) {
                rows.add(new FlywayHistoryRow(
                        result.getInt("installed_rank"),
                        result.getString("version"),
                        result.getString("description"),
                        (Integer) result.getObject("checksum"),
                        result.getBoolean("success")));
            }
        }
        return rows;
    }

    private List<ProductClientSeed> productClientRows() throws SQLException {
        List<ProductClientSeed> rows = new ArrayList<>();
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT client_id, product, channel, enabled "
                             + "FROM identity.product_clients ORDER BY client_id")) {
            while (result.next()) {
                rows.add(new ProductClientSeed(
                        result.getString("client_id"),
                        result.getString("product"),
                        result.getString("channel"),
                        result.getBoolean("enabled")));
            }
        }
        return rows;
    }

    private void insertRuntimeAccount(IdentityRuntimeProperties properties, UUID accountId) throws SQLException {
        DataSource runtimeDataSource = new IdentityPersistenceConfiguration().dataSource(properties);
        try (Connection runtime = runtimeDataSource.getConnection();
             PreparedStatement statement = runtime.prepareStatement(
                     "INSERT INTO identity.platform_accounts (id, status, created_at, updated_at) "
                             + "VALUES (?, 'ACTIVE', ?, ?)")) {
            Timestamp timestamp = Timestamp.from(Instant.now());
            statement.setObject(1, accountId);
            statement.setTimestamp(2, timestamp);
            statement.setTimestamp(3, timestamp);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } finally {
            if (runtimeDataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception exception) {
                    throw new SQLException("Failed to close disposable runtime datasource", exception);
                }
            }
        }
    }

    private String platformAccountStatus(UUID accountId) throws SQLException {
        try (Connection admin = adminConnection(); PreparedStatement statement = admin.prepareStatement(
                "SELECT status FROM identity.platform_accounts WHERE id = ?")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString("status");
            }
        }
    }

    private boolean refreshTokenAccountUniquenessConstraintExists() throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM pg_constraint "
                             + "WHERE conrelid = 'identity.refresh_token'::regclass "
                             + "AND conname = 'refresh_token_account_id_key'")) {
            return result.next();
        }
    }

    private record FlywaySession(Flyway flyway, DataSource runtimeDataSource, DataSource flywayDataSource)
            implements AutoCloseable {

        @Override
        public void close() throws Exception {
            try {
                closeIfPossible(flywayDataSource);
            } finally {
                if (runtimeDataSource != flywayDataSource) {
                    closeIfPossible(runtimeDataSource);
                }
            }
        }

        private static void closeIfPossible(DataSource dataSource) throws Exception {
            if (dataSource instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private record FlywayHistoryRow(
            int installedRank, String version, String description, Integer checksum, boolean success) {
    }

    private record ProductClientSeed(String clientId, String product, String channel, boolean enabled) {
    }
}
