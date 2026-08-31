package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves that the real Platform Core host composes Identity persistence against PostgreSQL. */
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "server.port=0",
                "platform.identity.persistence.enabled=true",
                "platform.identity.auth.enabled=false",
                "platform.identity.redis-host=localhost",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=test:identity:"
        }
)
public class PlatformCoreIdentityPersistenceBootIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void realPlatformCoreHostMigratesAndUsesIdentityPersistence() throws SQLException {
        assertThat(dataSource).isNotNull();
        assertThat(applicationContext.containsBean("flyway")).isTrue();
        assertThat(applicationContext.containsBean("entityManagerFactory")).isTrue();
        assertThat(applicationContext.containsBean("platformAccountJpaRepository")).isTrue();
        assertThat(applicationContext.containsBean("passwordCredentialJpaRepository")).isTrue();
        assertThat(applicationContext.getBeansOfType(AccountStatusPort.class)).isNotEmpty();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(readSingleValue(statement,
                    "select to_regclass('public.platform_accounts')"))
                    .isEqualTo("platform_accounts");
            assertThat(readSingleValue(statement,
                    "select to_regclass('public.password_credentials')"))
                    .isEqualTo("password_credentials");
            assertThat(Integer.parseInt(readSingleValue(statement,
                    "select cast(count(*) as varchar) from flyway_schema_history where success = true")))
                    .isPositive();
        }
    }

    private String readSingleValue(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
