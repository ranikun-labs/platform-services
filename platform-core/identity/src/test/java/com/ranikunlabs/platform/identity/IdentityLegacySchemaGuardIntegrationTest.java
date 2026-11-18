package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityLegacySchemaDetectedException;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityLegacySchemaGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the pre-Flyway guard prevents a public Identity history fork. */
class IdentityLegacySchemaGuardIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID LEGACY_ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + IdentityDatabaseSchema.NAME + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
            for (String table : IdentityDatabaseSchema.TABLES) {
                statement.execute("DROP TABLE IF EXISTS public." + table + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.unrelated_table CASCADE");
        }
    }

    @Test
    void freshDatabaseAllowsMigrationIntoIdentitySchema() throws SQLException {
        guardedMigration();

        assertIdentityTablesPresent();
        assertPublicIdentityTablesAbsent();
    }

    @Test
    void correctIdentityDatabaseAllowsIdempotentRepeatMigration() throws SQLException {
        guardedMigration();

        guardedMigration();

        assertIdentityTablesPresent();
        assertPublicIdentityTablesAbsent();
    }

    @Test
    void legacyPublicDatabaseFailsBeforeIdentityObjectsAreCreated() throws SQLException {
        buildLegacyPublicState();

        assertThatThrownBy(this::startIdentityApplication)
                .satisfies(failure -> {
                    assertThat(hasCause(failure, IdentityLegacySchemaDetectedException.class)).isTrue();
                    assertThat(failure.toString()).containsIgnoringCase("legacy");
                });

        assertLegacyPublicStatePreserved();
        assertIdentitySchemaAbsent();
    }

    @Test
    void mixedPublicAndIdentityStateFailsClosed() throws SQLException {
        guardedMigration();
        buildLegacyPublicState();

        assertThatThrownBy(() -> new IdentityLegacySchemaGuard().migrate(currentFlyway()))
                .isInstanceOf(IdentityLegacySchemaDetectedException.class);

        assertLegacyPublicStatePreserved();
        assertIdentityTablesPresent();
    }

    @Test
    void unrelatedPublicObjectsDoNotTriggerTheIdentityGuard() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.unrelated_table (id integer PRIMARY KEY)");
        }

        guardedMigration();

        assertIdentityTablesPresent();
        assertPublicIdentityTablesAbsent();
        assertThat(tableExists("public", "unrelated_table")).isTrue();
    }

    @Test
    void restrictedMigratorStillDetectsLegacyIdentityTablesWithoutPublicSchemaAccess() throws SQLException {
        createRestrictedMigratorRole();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.platform_accounts (id uuid PRIMARY KEY)");
        }

        assertThatThrownBy(() -> new IdentityLegacySchemaGuard().migrate(restrictedMigratorFlyway()))
                .isInstanceOf(IdentityLegacySchemaDetectedException.class);

        assertThat(publicTableSelectDenied()).isTrue();
        assertIdentitySchemaAbsent();
        dropRestrictedMigratorRole();
    }

    @Test
    void restrictedMigratorFailsClosedWhenLegacyHistoryExistsButCannotBeRead() throws SQLException {
        createRestrictedMigratorRole();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.flyway_schema_history (version varchar(50), description varchar(200))");
        }

        assertThatThrownBy(() -> new IdentityLegacySchemaGuard().migrate(restrictedMigratorFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to verify legacy Shared Identity persistence");

        assertThat(tableExists("public", IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE)).isTrue();
        assertIdentitySchemaAbsent();
        dropRestrictedMigratorRole();
    }

    private void buildLegacyPublicState() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO public.platform_accounts "
                             + "(id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")) {
            OffsetDateTime now = OffsetDateTime.now();
            statement.setObject(1, LEGACY_ACCOUNT_ID);
            statement.setObject(2, now);
            statement.setObject(3, now);
            statement.executeUpdate();
        }
    }

    private void guardedMigration() {
        new IdentityLegacySchemaGuard().migrate(currentFlyway());
    }

    private Flyway currentFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(IdentityDatabaseSchema.NAME)
                .defaultSchema(IdentityDatabaseSchema.NAME)
                .createSchemas(true)
                .cleanDisabled(false)
                .load();
    }

    private Flyway restrictedMigratorFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "identity_guard_migrator_test", "guard_test_password")
                .locations("classpath:db/migration")
                .schemas(IdentityDatabaseSchema.NAME)
                .defaultSchema(IdentityDatabaseSchema.NAME)
                .createSchemas(false)
                .load();
    }

    private void createRestrictedMigratorRole() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE identity_guard_migrator_test LOGIN PASSWORD 'guard_test_password'");
            statement.execute("REVOKE ALL ON SCHEMA public FROM identity_guard_migrator_test");
        }
    }

    private void dropRestrictedMigratorRole() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ROLE identity_guard_migrator_test");
        }
    }

    private boolean publicTableSelectDenied() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "identity_guard_migrator_test", "guard_test_password");
             Statement statement = connection.createStatement()) {
            return hasSqlState(statement, "SELECT 1 FROM public.platform_accounts", "42501");
        }
    }

    private boolean hasSqlState(Statement statement, String sql, String expectedState) throws SQLException {
        try {
            statement.executeQuery(sql);
            return false;
        } catch (SQLException exception) {
            return expectedState.equals(exception.getSQLState());
        }
    }

    private void startIdentityApplication() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "platform.identity.persistence.enabled=true",
                        "platform.identity.auth.enabled=false",
                        "platform.identity.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "platform.identity.datasource.username=" + POSTGRES.getUsername(),
                        "platform.identity.datasource.password=" + POSTGRES.getPassword(),
                        "platform.identity.redis-host=localhost",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=test:identity:"
                )
                .run()) {
            throw new AssertionError("Identity startup unexpectedly succeeded for legacy public state");
        }
    }

    private void assertLegacyPublicStatePreserved() throws SQLException {
        Set<String> expectedPublicTables = new HashSet<>(IdentityDatabaseSchema.TABLES);
        expectedPublicTables.add(IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE);
        try (Connection connection = connection()) {
            assertThat(tableNames(connection, "public"))
                    .containsAll(expectedPublicTables);
            assertThat(accountRowExists(connection, LEGACY_ACCOUNT_ID)).isTrue();
        }
    }

    private void assertIdentityTablesPresent() throws SQLException {
        Set<String> expectedIdentityTables = new HashSet<>(IdentityDatabaseSchema.TABLES);
        expectedIdentityTables.add(IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE);
        try (Connection connection = connection()) {
            assertThat(tableNames(connection, IdentityDatabaseSchema.NAME))
                    .containsExactlyInAnyOrderElementsOf(expectedIdentityTables);
        }
    }

    private void assertPublicIdentityTablesAbsent() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(tableNames(connection, "public"))
                    .doesNotContainAnyElementsOf(new HashSet<>(IdentityDatabaseSchema.TABLES));
            assertThat(tableExists("public", IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE)).isFalse();
        }
    }

    private void assertIdentitySchemaAbsent() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(tableNames(connection, IdentityDatabaseSchema.NAME)).isEmpty();
            assertThat(schemaExists(connection, IdentityDatabaseSchema.NAME)).isFalse();
        }
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

    private boolean accountRowExists(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM public.platform_accounts WHERE id = ?")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean tableExists(String schema, String table) throws SQLException {
        try (Connection connection = connection()) {
            return tableExists(connection, schema, table);
        }
    }

    private boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(IdentityModuleConfiguration.class)
    static class TestApplication {
    }
}
