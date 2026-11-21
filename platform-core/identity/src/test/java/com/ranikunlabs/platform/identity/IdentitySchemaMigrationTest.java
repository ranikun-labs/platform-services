package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the standalone post-V6 Identity schema without importing Carelog product tables. */
class IdentitySchemaMigrationTest {

    private static final Set<String> IDENTITY_TABLES = Set.of(
            "platform_accounts",
            "password_credentials",
            "external_identities",
            "refresh_token",
            "product_clients",
            "carelog_registration_operation"
    );
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetSchema() throws SQLException {
        flywayConfiguration().createSchemas(true).load().clean();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + IdentityDatabaseSchema.NAME);
        }
    }

    @Test
    void freshProvisionedIdentitySchemaCreatesOnlyEffectiveIdentityTables() throws Exception {
        var result = flyway().migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertThat(flyway().migrate().migrationsExecuted).isZero();

        try (Connection connection = connection()) {
            Set<String> expectedTables = new HashSet<>(IDENTITY_TABLES);
            expectedTables.add("flyway_schema_history");
            assertThat(tableNames(connection, IdentityDatabaseSchema.NAME))
                    .containsExactlyInAnyOrderElementsOf(expectedTables);
            assertThat(tableNames(connection, "public"))
                    .doesNotContainAnyElementsOf(IDENTITY_TABLES);

            assertColumn(connection, "platform_accounts", "id", "uuid", "NO", null);
            assertColumn(connection, "platform_accounts", "status", "varchar", "NO", 20);
            assertColumn(connection, "platform_accounts", "primary_email", "varchar", "YES", 255);
            assertColumn(connection, "password_credentials", "account_id", "uuid", "NO", null);
            assertColumn(connection, "password_credentials", "login_id", "varchar", "NO", 255);
            assertColumn(connection, "password_credentials", "password_hash", "varchar", "NO", 255);
            assertColumn(connection, "external_identities", "provider", "varchar", "NO", 50);
            assertColumn(connection, "external_identities", "provider_subject", "varchar", "NO", 255);
            assertColumn(connection, "refresh_token", "refresh_token", "varchar", "NO", 500);
            assertColumn(connection, "refresh_token", "account_id", "uuid", "NO", null);
            assertColumn(connection, "refresh_token", "user_id", "varchar", "YES", 255);
            assertColumn(connection, "product_clients", "client_id", "varchar", "NO", 100);
            assertColumn(connection, "product_clients", "product", "varchar", "NO", 50);
            assertColumn(connection, "product_clients", "channel", "varchar", "NO", 50);
            assertColumn(connection, "product_clients", "enabled", "bool", "NO", null);

            for (String constraint : Set.of(
                    "platform_accounts_pkey",
                    "platform_accounts_status_check",
                    "password_credentials_pkey",
                    "password_credentials_login_id_key",
                    "fk_password_credentials_account",
                    "external_identities_pkey",
                    "external_identities_provider_subject_key",
                    "fk_external_identities_account",
                    "refresh_token_pkey",
                    "refresh_token_refresh_token_key",
                    "refresh_token_account_id_key",
                    "fk_refresh_token_account",
                    "uq_product_clients_client_id",
                    "chk_product_clients_client_id_format",
                    "chk_product_clients_product",
                    "chk_product_clients_channel"
            )) {
                assertConstraint(connection, constraint);
            }

            assertIndex(connection, "idx_external_identities_account_id");
            assertIndex(connection, "idx_refresh_token_account_id");
            assertSequence(connection, "external_identities_id_seq");
            assertSequence(connection, "refresh_token_refresh_token_id_seq");
            assertSequence(connection, "product_clients_id_seq");

            assertThat(foreignKeysReferencingCarelogTables(connection)).isZero();
            assertThat(tableNames(connection, IdentityDatabaseSchema.NAME)).doesNotContain(
                    "users", "customers", "relations", "journals", "customer_event"
            );
        }

        assertMigrationHasNoCarelogTableReferences();
    }

    @Test
    void existingDevelopmentHistoryAndRowsSurviveTheSchemaProvisioningChange() throws Exception {
        Flyway previousConfiguration = flywayConfiguration().createSchemas(true).target("2").load();
        assertThat(previousConfiguration.migrate().migrationsExecuted).isEqualTo(2);

        UUID accountId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAccount(connection, accountId, "ACTIVE");
        }
        Map<String, Integer> checksumsBefore = migrationChecksums();

        Flyway currentConfiguration = flyway();
        assertThat(currentConfiguration.migrate().migrationsExecuted).isEqualTo(1);
        currentConfiguration.validate();

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM platform_accounts WHERE id = ?")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("ACTIVE");
            }
        }
        assertThat(migrationChecksums()).containsAllEntriesOf(checksumsBefore).containsKey("3");
    }

    @Test
    void productClientSeedAndDatabaseChecksMatchCanonicalRegistry() throws Exception {
        flyway().migrate();

        try (Connection connection = connection()) {
            assertThat(readProductClient(connection, "carelog-web"))
                    .containsExactly("CARELOG", "WEB", "true");
            assertThat(readProductClient(connection, "carelog-mobile"))
                    .containsExactly("CARELOG", "MOBILE", "true");

            UUID accountId = UUID.randomUUID();
            insertAccount(connection, accountId, "ACTIVE");
            insertCredential(connection, accountId, "schema-login", "hash");

            assertThatThrownBy(() -> insertCredential(connection, UUID.randomUUID(), "orphan-login", "hash"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        }

        assertSqlState("INSERT INTO platform_accounts (id, status, created_at, updated_at) VALUES (?, ?, ?, ?)",
                "23514", UUID.randomUUID(), "BROKEN", now(), now());

        UUID firstAccount = UUID.randomUUID();
        UUID secondAccount = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAccount(connection, firstAccount, "ACTIVE");
            insertAccount(connection, secondAccount, "ACTIVE");
            insertCredential(connection, firstAccount, "duplicate-login", "hash");
        }
        assertSqlState(
                "INSERT INTO password_credentials (account_id, login_id, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "23505", secondAccount, "duplicate-login", "hash", now(), now()
        );

        assertSqlState(
                "INSERT INTO product_clients (client_id, product, channel, enabled) VALUES (?, ?, ?, ?)",
                "23514", "invalid client", "CARELOG", "WEB", true
        );
        assertSqlState(
                "INSERT INTO product_clients (client_id, product, channel, enabled) VALUES (?, ?, ?, ?)",
                "23514", "invalid-product", "NOT_A_PRODUCT", "WEB", true
        );
        assertSqlState(
                "INSERT INTO product_clients (client_id, product, channel, enabled) VALUES (?, ?, ?, ?)",
                "23514", "invalid-channel", "CARELOG", "DESKTOP", true
        );
    }

    @Test
    void internalForeignKeysAndRefreshTokenInvariantsAreEnforced() throws Exception {
        flyway().migrate();

        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAccount(connection, accountId, "ACTIVE");
            insertAccount(connection, otherAccountId, "ACTIVE");
            insertRefreshToken(connection, "schema-refresh", accountId, null);
            insertRefreshToken(connection, "other-account-refresh", otherAccountId, null);
        }

        assertSqlState(
                "INSERT INTO refresh_token (refresh_token, account_id, token_expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "23505", "different-refresh", accountId, now().plusDays(1), now(), now()
        );
        assertSqlState(
                "INSERT INTO refresh_token (refresh_token, account_id, token_expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "23503", "orphan-refresh", UUID.randomUUID(), now().plusDays(1), now(), now()
        );
        assertSqlState(
                "INSERT INTO refresh_token (refresh_token, account_id, token_expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "23502", "missing-account", null, now().plusDays(1), now(), now()
        );

        assertSqlState(
                "INSERT INTO external_identities (account_id, provider, provider_subject, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "23503", UUID.randomUUID(), "kakao", "orphan-subject", now(), now()
        );
    }

    @Test
    void upgradingV1WithDuplicateAccountSessionsFailsClosed() throws Exception {
        Flyway v1 = flywayConfiguration()
                .target(MigrationVersion.fromVersion("1"))
                .load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);

        UUID accountId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAccount(connection, accountId, "ACTIVE");
            insertRefreshToken(connection, "legacy-refresh-one", accountId, null);
            insertRefreshToken(connection, "legacy-refresh-two", accountId, null);
        }

        assertThatThrownBy(() -> flyway().migrate())
                .satisfies(failure -> assertThat(hasSqlState(failure, "23505")).isTrue());
    }

    private Flyway flyway() {
        return flywayConfiguration().load();
    }

    private FluentConfiguration flywayConfiguration() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(IdentityDatabaseSchema.NAME)
                .defaultSchema(IdentityDatabaseSchema.NAME)
                .createSchemas(false)
                .cleanDisabled(false);
    }

    private Map<String, Integer> migrationChecksums() throws SQLException {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version, checksum FROM identity.flyway_schema_history "
                             + "WHERE success ORDER BY installed_rank")) {
            while (result.next()) {
                checksums.put(result.getString("version"), result.getInt("checksum"));
            }
        }
        return checksums;
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setSchema(IdentityDatabaseSchema.NAME);
        return connection;
    }

    private Set<String> tableNames(Connection connection, String schema) throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private void assertColumn(
            Connection connection, String table, String column, String udtName, String nullable, Integer length
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT udt_name, is_nullable, character_maximum_length "
                        + "FROM information_schema.columns WHERE table_schema = ? "
                        + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, IdentityDatabaseSchema.NAME);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("column %s.%s exists", table, column).isTrue();
                assertThat(result.getString("udt_name")).isEqualTo(udtName);
                assertThat(result.getString("is_nullable")).isEqualTo(nullable);
                Integer actualLength = (Integer) result.getObject("character_maximum_length");
                assertThat(actualLength).isEqualTo(length);
            }
        }
    }

    private void assertConstraint(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_constraint c "
                        + "JOIN pg_class table_ref ON table_ref.oid = c.conrelid "
                        + "JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace "
                        + "WHERE schema_ref.nspname = ? AND c.conname = ?")) {
            statement.setString(1, IdentityDatabaseSchema.NAME);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("constraint %s exists", name).isTrue();
            }
        }
    }

    private void assertIndex(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname = ? AND indexname = ?")) {
            statement.setString(1, IdentityDatabaseSchema.NAME);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index %s exists", name).isTrue();
            }
        }
    }

    private void assertSequence(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_class relation_ref "
                        + "JOIN pg_namespace schema_ref ON schema_ref.oid = relation_ref.relnamespace "
                        + "WHERE relation_ref.relkind = 'S' "
                        + "AND schema_ref.nspname = ? AND relation_ref.relname = ?")) {
            statement.setString(1, IdentityDatabaseSchema.NAME);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("sequence %s exists", name).isTrue();
            }
        }
    }

    private int foreignKeysReferencingCarelogTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM pg_constraint c "
                             + "JOIN pg_class referenced ON referenced.oid = c.confrelid "
                             + "WHERE c.contype = 'f' AND referenced.relname IN "
                             + "('users', 'customers', 'relations', 'journals', 'customer_event')")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void assertMigrationHasNoCarelogTableReferences() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V1__identity_schema.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(migration).doesNotContain("users", "customers", "relations", "journals", "customer_event");
        }
    }

    private String[] readProductClient(Connection connection, String clientId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product, channel, enabled FROM product_clients WHERE client_id = ?")) {
            statement.setString(1, clientId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new String[]{result.getString("product"), result.getString("channel"),
                        Boolean.toString(result.getBoolean("enabled"))};
            }
        }
    }

    private void insertAccount(Connection connection, UUID id, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO platform_accounts (id, status, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, status);
            statement.setObject(3, now());
            statement.setObject(4, now());
            statement.executeUpdate();
        }
    }

    private void insertCredential(Connection connection, UUID accountId, String loginId, String passwordHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO password_credentials (account_id, login_id, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setObject(1, accountId);
            statement.setString(2, loginId);
            statement.setString(3, passwordHash);
            statement.setObject(4, now());
            statement.setObject(5, now());
            statement.executeUpdate();
        }
    }

    private void insertRefreshToken(Connection connection, String token, UUID accountId, String userId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO refresh_token "
                        + "(refresh_token, account_id, user_id, token_expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, token);
            statement.setObject(2, accountId);
            statement.setString(3, userId);
            statement.setObject(4, now().plusDays(1));
            statement.setObject(5, now());
            statement.setObject(6, now());
            statement.executeUpdate();
        }
    }

    private void assertSqlState(String sql, String expectedState, Object... values) {
        assertThatThrownBy(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                statement.executeUpdate();
            }
        }).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo(expectedState));
    }

    private boolean hasSqlState(Throwable failure, String expectedState) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && expectedState.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
