package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityRuntimeOnlyTest {
    private final IdentityRuntimeProperties.Datasource runtime =
            new IdentityRuntimeProperties.Datasource("jdbc:postgresql://example/app", "app", "password");

    private IdentityRuntimeProperties properties(IdentityRuntimeProperties.FlywayDatasource migration) {
        return new IdentityRuntimeProperties(runtime, migration, "localhost", 6379, "test", false);
    }

    @Test
    void runtimeOnlyRequiresExplicitFlywayDisable() {
        var env = new MockEnvironment().withProperty("platform.identity.runtime-only", "true");
        assertThatThrownBy(() -> IdentityPersistenceConfiguration.validateRuntimeOnly(properties(null), env))
                .hasMessageContaining("requires spring.flyway.enabled=false");
        env.setProperty("spring.flyway.enabled", "true");
        assertThatThrownBy(() -> IdentityPersistenceConfiguration.validateRuntimeOnly(properties(null), env))
                .hasMessageContaining("requires spring.flyway.enabled=false");
        env.setProperty("spring.flyway.enabled", "false");
        assertThatCode(() -> IdentityPersistenceConfiguration.validateRuntimeOnly(properties(null), env))
                .doesNotThrowAnyException();
    }

    @Test
    void runtimeOnlyRejectsAnyMigrationCredentials() {
        var env = new MockEnvironment().withProperty("platform.identity.runtime-only", "true")
                .withProperty("spring.flyway.enabled", "false");
        var migration = new IdentityRuntimeProperties.FlywayDatasource(null, "owner", null);
        assertThatThrownBy(() -> IdentityPersistenceConfiguration.validateRuntimeOnly(properties(migration), env))
                .hasMessageContaining("forbids migration datasource credentials");
    }

    @Test
    void existingMigrationModeRemainsAvailable() {
        var migration = new IdentityRuntimeProperties.FlywayDatasource("jdbc:postgresql://example/app", "owner", "password");
        assertThatCode(() -> IdentityPersistenceConfiguration.validateRuntimeOnly(properties(migration), new MockEnvironment()))
                .doesNotThrowAnyException();
    }
}
