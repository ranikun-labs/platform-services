package com.ranikunlabs.platform.core;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCoreVendorUnwrapCompatibilityTest {

    @Test
    void preservesDriverUnwrapAndMakesItsObservationBoundaryExplicit() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        Class<?> dataSourceType = Class.forName(
                "com.ranikunlabs.platform.identity.adapter.persistence.InstrumentedHikariDataSource");
        try (AutoCloseable dataSourceResource = (AutoCloseable) dataSourceType
                .getConstructor()
                .newInstance()) {
            dataSourceType.getMethod("setJdbcUrl", String.class)
                    .invoke(dataSourceResource,
                            "jdbc:h2:mem:vendor-unwrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            dataSourceType.getMethod("setUsername", String.class).invoke(dataSourceResource, "sa");
            dataSourceType.getMethod("setPassword", String.class).invoke(dataSourceResource, "");
            dataSourceType.getMethod("setMeterRegistry", MeterRegistry.class).invoke(dataSourceResource, registry);
            DataSource dataSource = (DataSource) dataSourceResource;

            try (Connection observed = dataSource.getConnection()) {
                Class<?> h2VendorConnection = Class.forName("org.h2.jdbc.JdbcConnection");
                assertThat(observed.isWrapperFor(h2VendorConnection)).isTrue();
                Object nativeConnection = observed.unwrap(h2VendorConnection);
                assertThat(h2VendorConnection.isInstance(nativeConnection)).isTrue();

                long observedCountBefore = queryCount(registry);
                try (Statement statement = observed.createStatement();
                     ResultSet selected = statement.executeQuery("SELECT 1")) {
                    assertThat(selected.next()).isTrue();
                }
                assertThat(queryCount(registry)).isEqualTo(observedCountBefore + 1);

                long nativeCountBefore = queryCount(registry);
                try (Statement statement = (Statement) h2VendorConnection
                             .getMethod("createStatement")
                             .invoke(nativeConnection);
                     ResultSet selected = statement.executeQuery("SELECT 1")) {
                    assertThat(selected.next()).isTrue();
                }
                assertThat(queryCount(registry)).isEqualTo(nativeCountBefore);
            }
        }
    }

    private long queryCount(MeterRegistry registry) {
        var timer = registry.find("ranikun.db.query.duration")
                .tags("operation", "query", "outcome", "success")
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
