package com.ranikunlabs.platform.identity.adapter.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JDBC handle proxies that time only bounded Statement and DatabaseMetaData calls. */
final class JdbcStatementObservation {

    static final String METRIC_NAME = "ranikun.db.query.duration";
    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private JdbcStatementObservation() {
    }

    static Connection wrapConnection(Connection delegate, MeterRegistry registry) {
        if (registry == null) {
            return delegate;
        }
        return (Connection) new ConnectionHandler(delegate, registry).proxy();
    }

    private static String statementOperation(Method method) {
        return switch (method.getName()) {
            case "executeQuery" -> "query";
            case "executeUpdate", "executeLargeUpdate" -> "update";
            case "executeBatch", "executeLargeBatch" -> "batch";
            case "execute" -> "execute";
            default -> null;
        };
    }

    private static boolean returnsResultSet(Method method) {
        return ResultSet.class.isAssignableFrom(method.getReturnType());
    }

    private static Object invokeTarget(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            // In particular, preserve the driver's original SQLException and SQLSTATE.
            throw failure.getCause();
        }
    }

    private static Object invokeMeasured(
            MeterRegistry registry,
            String operation,
            Method method,
            Object target,
            Object[] arguments
    ) throws Throwable {
        long started = System.nanoTime();
        String outcome = SUCCESS;
        try {
            return invokeTarget(method, target, arguments);
        } catch (Throwable failure) {
            outcome = ERROR;
            throw failure;
        } finally {
            try {
                registry.timer(METRIC_NAME, "operation", operation, "outcome", outcome)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                // Instrumentation is best effort and must never replace a JDBC result or exception.
            }
        }
    }

    private abstract static class ProxyHandler implements InvocationHandler {

        private Object proxy;

        final void setProxy(Object proxy) {
            this.proxy = proxy;
        }

        final Object proxy() {
            return proxy;
        }

        @Override
        public final Object invoke(Object ignored, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(method.getName(), arguments);
            }
            if (method.getDeclaringClass() == Wrapper.class) {
                return wrapperMethod(method, arguments);
            }
            return invokeJdbc(method, arguments == null ? new Object[0] : arguments);
        }

        private Object objectMethod(String name, Object[] arguments) {
            return switch (name) {
                case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName()
                        + "Observation@" + Integer.toHexString(System.identityHashCode(proxy));
                default -> throw new IllegalStateException("Unexpected Object method: " + name);
            };
        }

        private Object wrapperMethod(Method method, Object[] arguments) throws SQLException {
            Class<?> requestedType = (Class<?>) arguments[0];
            if (requestedType == null) {
                throw new SQLException("JDBC wrapper type must not be null");
            }
            if ("isWrapperFor".equals(method.getName())) {
                return requestedType.isInstance(proxy);
            }
            if (requestedType.isInstance(proxy)) {
                return proxy;
            }
            // Returning a driver delegate here would let callers bypass every observation proxy.
            throw new SQLException("Requested JDBC wrapper type is not exposed by the observing handle");
        }

        abstract Object invokeJdbc(Method method, Object[] arguments) throws Throwable;
    }

    private static final class ConnectionHandler extends ProxyHandler {

        private final Connection delegate;
        private final MeterRegistry registry;
        private final Map<Statement, StatementHandler> statements = new IdentityHashMap<>();
        private DatabaseMetaDataHandler metadata;

        private ConnectionHandler(Connection delegate, MeterRegistry registry) {
            this.delegate = delegate;
            this.registry = registry;
            setProxy(Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this));
        }

        @Override
        Object invokeJdbc(Method method, Object[] arguments) throws Throwable {
            Object result = invokeTarget(method, delegate, arguments);
            if (result instanceof Statement statement) {
                return wrapStatement(statement);
            }
            if (result instanceof DatabaseMetaData databaseMetaData) {
                return wrapMetadata(databaseMetaData);
            }
            if ("close".equals(method.getName()) || "abort".equals(method.getName())) {
                statements.clear();
                metadata = null;
            }
            return result;
        }

        private Statement wrapStatement(Statement statement) {
            if (isObservedProxy(statement)) {
                return statement;
            }
            StatementHandler handler = statements.get(statement);
            if (handler == null) {
                handler = new StatementHandler(statement, this, registry);
                statements.put(statement, handler);
            }
            return (Statement) handler.proxy();
        }

        private void releaseStatement(Statement statement, StatementHandler handler) {
            if (statements.get(statement) == handler) {
                statements.remove(statement);
            }
        }

        private void releaseResultSet(ResultSet resultSet, ResultSetHandler handler) {
            if (handler.statement != null) {
                handler.statement.releaseResultSet(resultSet, handler);
            }
        }

        private ResultSet wrapMetadataResultSet(ResultSet resultSet) {
            return isObservedProxy(resultSet)
                    ? resultSet
                    : (ResultSet) new ResultSetHandler(resultSet, this, null).proxy();
        }

        private DatabaseMetaData wrapMetadata(DatabaseMetaData databaseMetaData) {
            if (isObservedProxy(databaseMetaData)) {
                return databaseMetaData;
            }
            if (metadata == null || metadata.delegate != databaseMetaData) {
                metadata = new DatabaseMetaDataHandler(databaseMetaData, this, registry);
            }
            return (DatabaseMetaData) metadata.proxy();
        }
    }

    private static final class StatementHandler extends ProxyHandler {

        private final Statement delegate;
        private final ConnectionHandler connection;
        private final MeterRegistry registry;
        private final Map<ResultSet, ResultSetHandler> resultSets = new IdentityHashMap<>();

        private StatementHandler(Statement delegate, ConnectionHandler connection, MeterRegistry registry) {
            this.delegate = delegate;
            this.connection = connection;
            this.registry = registry;
            Class<?> statementType = delegate instanceof CallableStatement
                    ? CallableStatement.class
                    : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            setProxy(Proxy.newProxyInstance(
                    statementType.getClassLoader(), new Class<?>[]{statementType}, this));
        }

        @Override
        Object invokeJdbc(Method method, Object[] arguments) throws Throwable {
            String operation = statementOperation(method);
            if (operation != null) {
                // JDBC statements have one current result set; do not retain old handles across executions.
                resultSets.clear();
            } else if ("getMoreResults".equals(method.getName())) {
                releaseMoreResults(arguments);
            }
            Object result = operation == null
                    ? invokeTarget(method, delegate, arguments)
                    : invokeMeasured(registry, operation, method, delegate, arguments);
            if ("getConnection".equals(method.getName()) && method.getParameterCount() == 0) {
                return connection.proxy();
            }
            if (result instanceof ResultSet resultSet) {
                return wrapResultSet(resultSet);
            }
            if ("close".equals(method.getName())) {
                releaseIfClosed();
            }
            return result;
        }

        private void releaseIfClosed() {
            try {
                if (delegate.isClosed()) {
                    resultSets.clear();
                    connection.releaseStatement(delegate, this);
                }
            } catch (SQLException ignored) {
                // Resource cleanup must not replace the JDBC operation's original result or failure.
            }
        }

        private void releaseMoreResults(Object[] arguments) {
            int disposition = arguments.length == 0
                    ? Statement.CLOSE_CURRENT_RESULT
                    : (Integer) arguments[0];
            if (disposition == Statement.CLOSE_ALL_RESULTS) {
                resultSets.clear();
            } else if (disposition == Statement.CLOSE_CURRENT_RESULT) {
                try {
                    ResultSet current = delegate.getResultSet();
                    if (current != null) {
                        resultSets.remove(current);
                    }
                } catch (SQLException ignored) {
                    // Cache cleanup must not interfere with getMoreResults' JDBC result.
                }
            }
        }

        private void releaseResultSet(ResultSet resultSet, ResultSetHandler handler) {
            if (resultSets.get(resultSet) == handler) {
                resultSets.remove(resultSet);
            }
        }

        private ResultSet wrapResultSet(ResultSet resultSet) {
            if (isObservedProxy(resultSet)) {
                return resultSet;
            }
            ResultSetHandler handler = resultSets.get(resultSet);
            if (handler == null) {
                handler = new ResultSetHandler(resultSet, connection, this);
                resultSets.put(resultSet, handler);
            }
            return (ResultSet) handler.proxy();
        }
    }

    private static final class ResultSetHandler extends ProxyHandler {

        private final ResultSet delegate;
        private final ConnectionHandler connection;
        private final StatementHandler statement;

        private ResultSetHandler(
                ResultSet delegate,
                ConnectionHandler connection,
                StatementHandler statement
        ) {
            this.delegate = delegate;
            this.connection = connection;
            this.statement = statement;
            setProxy(Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, this));
        }

        @Override
        Object invokeJdbc(Method method, Object[] arguments) throws Throwable {
            if ("getStatement".equals(method.getName()) && method.getParameterCount() == 0) {
                if (statement != null) {
                    return statement.proxy();
                }
                Statement parent = delegate.getStatement();
                return parent == null ? null : connection.wrapStatement(parent);
            }
            Object result = invokeTarget(method, delegate, arguments);
            if ("close".equals(method.getName())) {
                connection.releaseResultSet(delegate, this);
                if (statement != null) {
                    statement.releaseIfClosed();
                }
            }
            return result;
        }
    }

    private static final class DatabaseMetaDataHandler extends ProxyHandler {

        private final DatabaseMetaData delegate;
        private final ConnectionHandler connection;
        private final MeterRegistry registry;

        private DatabaseMetaDataHandler(
                DatabaseMetaData delegate,
                ConnectionHandler connection,
                MeterRegistry registry
        ) {
            this.delegate = delegate;
            this.connection = connection;
            this.registry = registry;
            setProxy(Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, this));
        }

        @Override
        Object invokeJdbc(Method method, Object[] arguments) throws Throwable {
            Object result = returnsResultSet(method)
                    ? invokeMeasured(registry, "metadata", method, delegate, arguments)
                    : invokeTarget(method, delegate, arguments);
            if ("getConnection".equals(method.getName()) && method.getParameterCount() == 0) {
                return connection.proxy();
            }
            if (result instanceof ResultSet resultSet) {
                return connection.wrapMetadataResultSet(resultSet);
            }
            return result;
        }
    }

    private static boolean isObservedProxy(Object value) {
        return value != null
                && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof ProxyHandler;
    }
}
