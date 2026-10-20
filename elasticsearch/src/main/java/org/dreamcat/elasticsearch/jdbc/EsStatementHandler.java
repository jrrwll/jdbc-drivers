package org.dreamcat.elasticsearch.jdbc;

import org.dreamcat.elasticsearch.client.EsSearchClient;
import org.dreamcat.elasticsearch.client.EsUpdateClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

final class EsStatementHandler implements InvocationHandler {

    private final Connection connection;
    private final EsStatementExecutor executor;
    private boolean closed;

    EsStatementHandler(Connection connection, EsSearchClient searchClient, EsUpdateClient updateClient) {
        this.connection = connection;
        this.executor = new EsStatementExecutor(connection, searchClient, updateClient);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ESStatement");
        switch (method.getName()) {
            case "close":
                closed = true;
                executor.close();
                return null;
            case "isClosed":
                return closed || connection.isClosed();
        }
        checkOpen();
        switch (method.getName()) {
            case "executeQuery":
                requireSql(args);
                return executor.executeQuery((String) args[0]);
            case "execute":
                requireSql(args);
                checkGeneratedKeys(method, args);
                return executor.execute((String) args[0]);
            case "executeUpdate":
                requireSql(args);
                checkGeneratedKeys(method, args);
                return (int) executor.executeUpdate((String) args[0]);
            case "executeLargeUpdate":
                requireSql(args);
                checkGeneratedKeys(method, args);
                return executor.executeUpdate((String) args[0]);
            case "getResultSet":
                return executor.getResultSet();
            case "getConnection":
                return connection;
            case "getResultSetType":
                return ResultSet.TYPE_FORWARD_ONLY;
            case "getResultSetConcurrency":
                return ResultSet.CONCUR_READ_ONLY;
            case "getResultSetHoldability":
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getUpdateCount":
                return (int) Math.min(executor.getUpdateCount(), Integer.MAX_VALUE);
            case "getLargeUpdateCount":
                return executor.getUpdateCount();
            case "setMaxRows":
            case "setLargeMaxRows":
                long value = ((Number) args[0]).longValue();
                if (value < 0) throw new SQLException("Maximum rows must not be negative");
                executor.setMaxRows(value);
                return null;
            case "getMaxRows":
                return (int) Math.min(executor.getMaxRows(), Integer.MAX_VALUE);
            case "getLargeMaxRows":
                return executor.getMaxRows();
            case "getMoreResults":
                int mode = args == null || args.length == 0 ? Statement.CLOSE_CURRENT_RESULT : (Integer) args[0];
                return executor.getMoreResults(mode);
            case "getWarnings":
                return executor.getWarnings();
            case "clearWarnings":
                executor.clearWarnings();
                return null;
            case "getFetchDirection":
                return ResultSet.FETCH_FORWARD;
            case "setFetchDirection":
                if (((Integer) args[0]) != ResultSet.FETCH_FORWARD)
                    throw new SQLFeatureNotSupportedException("Only forward fetch is supported");
                return null;
            case "setFetchSize":
            case "setEscapeProcessing":
            case "setCursorName":
            case "closeOnCompletion":
                return null;
            case "getFetchSize":
                return 0;
            case "isCloseOnCompletion":
                return false;
            case "isPoolable":
                return false;
            case "setPoolable":
                return null;
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                throw new SQLException("Not a wrapper for " + args[0]);
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                throw JdbcSupport.unsupported(method);
        }
    }

    private static void checkGeneratedKeys(Method method, Object[] args) throws SQLException {
        if (args.length == 1) return;
        if (args.length == 2 && args[1] instanceof Integer && (Integer) args[1] == Statement.NO_GENERATED_KEYS) {
            return;
        }
        throw new SQLFeatureNotSupportedException(method.getName() + " generated keys are not supported");
    }

    private static void requireSql(Object[] args) throws SQLException {
        if (args == null || args.length == 0 || !(args[0] instanceof String))
            throw new SQLException("SQL text is required");
    }

    private void checkOpen() throws SQLException {
        if (closed || connection.isClosed()) throw new SQLException("Statement or connection is closed");
    }
}
