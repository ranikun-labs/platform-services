package com.ranikunlabs.platform.identity.adapter.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Autowired;

/** Hikari pool whose borrowed JDBC handles retain a statement-observation boundary. */
public final class InstrumentedHikariDataSource extends HikariDataSource {

    private volatile MeterRegistry meterRegistry;

    /** Injected by Spring when the Prometheus/Actuator runtime is present. */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return JdbcStatementObservation.wrapConnection(super.getConnection(), meterRegistry);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return JdbcStatementObservation.wrapConnection(
                super.getConnection(username, password), meterRegistry);
    }
}
