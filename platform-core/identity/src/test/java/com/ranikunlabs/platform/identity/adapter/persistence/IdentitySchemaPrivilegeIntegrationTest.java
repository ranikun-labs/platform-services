package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies Identity migrations and runtime DML stay within their separate owned PostgreSQL schema. */
class IdentitySchemaPrivilegeIntegrationTest {

    private static final String MIGRATOR_ROLE = "identity_migrator_test";
    private static final String MIGRATOR_PASSWORD = "identity_migrator_test_password";
    private static final String RUNTIME_ROLE = "identity_runtime_test";
    private static final String RUNTIME_PASSWORD = "identity_runtime_test_password";
    private static final String CARELOG_OWNER = "carelog_owner_test";
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void provisionSchemasAndRoles() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR_ROLE + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + CARELOG_OWNER + " NOLOGIN");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA identity AUTHORIZATION " + MIGRATOR_ROLE);
            statement.execute("CREATE SCHEMA carelog AUTHORIZATION " + CARELOG_OWNER);
            statement.execute("SET ROLE " + CARELOG_OWNER);
            statement.execute("CREATE TABLE carelog.privilege_probe (id integer PRIMARY KEY, value text NOT NULL)");
            statement.execute("INSERT INTO carelog.privilege_probe VALUES (1, 'owner-data')");
            statement.execute("RESET ROLE");
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void migrationAndRuntimeRolesAreScopedToIdentity() throws Exception {
        IdentityRuntimeProperties properties = new IdentityRuntimeProperties(
                new IdentityRuntimeProperties.Datasource(
                        postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD),
                new IdentityRuntimeProperties.FlywayDatasource(
                        postgres.getJdbcUrl(), MIGRATOR_ROLE, MIGRATOR_PASSWORD),
                "localhost",
                6379,
                "identity:");

        IdentityPersistenceConfiguration configuration = new IdentityPersistenceConfiguration();
        FluentConfiguration flywayConfiguration = Flyway.configure();
        configuration.identityFlywayLocation(properties).customize(flywayConfiguration);
        Flyway identityFlyway = flywayConfiguration.load();

        configuration.identityFlywayMigrationStrategy(properties).migrate(identityFlyway);
        identityFlyway.validate();
        assertThat(flywayHistoryOwner()).isEqualTo(MIGRATOR_ROLE);
        DataSource runtimeDataSource = new IdentityPersistenceConfiguration().dataSource(properties);
        try {
            UUID accountId = UUID.randomUUID();
            try (Connection identity = runtimeDataSource.getConnection(); Statement statement = identity.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT current_user")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(RUNTIME_ROLE);
                }
                statement.execute("INSERT INTO identity.platform_accounts "
                        + "(id, status, created_at, updated_at) VALUES ('" + accountId + "', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO identity.external_identities "
                        + "(account_id, provider, provider_subject, created_at, updated_at) VALUES ('" + accountId
                        + "', 'kakao', 'runtime-privilege-probe', now(), now())");
                statement.execute("DELETE FROM identity.external_identities "
                        + "WHERE account_id = '" + accountId + "' AND provider_subject = 'runtime-privilege-probe'");
                statement.execute("UPDATE identity.platform_accounts SET status = 'INACTIVE' WHERE id = '" + accountId + "'");
                statement.execute("DELETE FROM identity.platform_accounts WHERE id = '" + accountId + "'");
            }

            assertPermissionDenied(runtimeDataSource, "CREATE SCHEMA identity_escape");
            assertPermissionDenied(runtimeDataSource, "CREATE TABLE identity.runtime_escape (id integer)");
            assertPermissionDenied(runtimeDataSource,
                    "ALTER TABLE identity.platform_accounts ADD COLUMN runtime_escape integer");
            assertPermissionDenied(runtimeDataSource, "CREATE TABLE carelog.identity_escape (id integer)");
            assertPermissionDenied(runtimeDataSource,
                    "UPDATE carelog.privilege_probe SET value = 'changed' WHERE id = 1");
            assertPermissionDenied(runtimeDataSource, "CREATE TABLE public.identity_escape (id integer)");
            assertThat(databaseCreatePrivilege()).isFalse();
            assertThat(schemaCreatePrivilege(IdentityDatabaseSchema.NAME)).isFalse();
            assertThat(schemaCreatePrivilege("carelog")).isFalse();
            assertThat(schemaUsagePrivilege(IdentityDatabaseSchema.NAME)).isTrue();
            assertThat(flywayHistorySelectPrivilege()).isFalse();
            assertThat(sequencePrivilege("identity.external_identities_id_seq", "USAGE")).isTrue();
            assertThat(sequencePrivilege("identity.external_identities_id_seq", "SELECT")).isFalse();

            try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT value FROM carelog.privilege_probe WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("value")).isEqualTo("owner-data");
            }
            assertThat(tableSchemaCount("platform_accounts", IdentityDatabaseSchema.NAME)).isEqualTo(1);
            assertThat(tableSchemaCount("platform_accounts", "public")).isZero();
            assertThat(tableSchemaCount("platform_accounts", "carelog")).isZero();
        } finally {
            if (runtimeDataSource instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @Test
    void partialFlywayCredentialsFailClosed() {
        IdentityRuntimeProperties properties = new IdentityRuntimeProperties(
                new IdentityRuntimeProperties.Datasource(
                        postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD),
                new IdentityRuntimeProperties.FlywayDatasource(postgres.getJdbcUrl(), MIGRATOR_ROLE, ""),
                "localhost",
                6379,
                "identity:");

        assertThatThrownBy(() -> new IdentityPersistenceConfiguration()
                .identityFlywayLocation(properties)
                .customize(Flyway.configure()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL, username, and password together");
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String flywayHistoryOwner() throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT pg_get_userbyid(relation.relowner) "
                             + "FROM pg_class relation JOIN pg_namespace schema_ref "
                             + "ON schema_ref.oid = relation.relnamespace "
                             + "WHERE schema_ref.nspname = 'identity' AND relation.relname = 'flyway_schema_history'")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void assertPermissionDenied(DataSource dataSource, String sql) {
        assertThatThrownBy(() -> {
            try (Connection identity = dataSource.getConnection(); Statement statement = identity.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }

    private boolean databaseCreatePrivilege() throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_database_privilege('" + RUNTIME_ROLE + "', current_database(), 'CREATE')")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private boolean schemaCreatePrivilege(String schema) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_schema_privilege('" + RUNTIME_ROLE + "', '" + schema + "', 'CREATE')")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private boolean schemaUsagePrivilege(String schema) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_schema_privilege('" + RUNTIME_ROLE + "', '" + schema + "', 'USAGE')")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private boolean flywayHistorySelectPrivilege() throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_table_privilege('" + RUNTIME_ROLE
                             + "', 'identity.flyway_schema_history', 'SELECT')")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private boolean sequencePrivilege(String sequence, String privilege) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT has_sequence_privilege('" + RUNTIME_ROLE + "', '" + sequence + "', '" + privilege + "')")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private int tableSchemaCount(String table, String schema) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.tables "
                             + "WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'")) {
            result.next();
            return result.getInt(1);
        }
    }
}
