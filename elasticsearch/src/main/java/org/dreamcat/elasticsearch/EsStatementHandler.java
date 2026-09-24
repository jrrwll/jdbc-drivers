package org.dreamcat.elasticsearch;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;

final class EsStatementHandler implements InvocationHandler {

    private final Connection connection;
    private final EsSearchClient client;
    private boolean closed;
    private ResultSet resultSet;
    private long maxRows;

    EsStatementHandler(Connection connection, EsSearchClient client) {
        this.connection = connection;
        this.client = client;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ES7Statement");
        switch (method.getName()) {
            case "close":
                closed = true;
                if (resultSet != null) resultSet.close();
                return null;
            case "isClosed":
                return closed || connection.isClosed();
        }
        checkOpen();
        switch (method.getName()) {
            case "executeQuery":
                requireSql(args);
                resultSet = execute((String) args[0]);
                return resultSet;
            case "execute":
                requireSql(args);
                resultSet = execute((String) args[0]);
                return true;
            case "getResultSet":
                return resultSet;
            case "getConnection":
                return connection;
            case "getResultSetType":
                return ResultSet.TYPE_FORWARD_ONLY;
            case "getResultSetConcurrency":
                return ResultSet.CONCUR_READ_ONLY;
            case "getResultSetHoldability":
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getUpdateCount":
                return -1;
            case "getLargeUpdateCount":
                return -1L;
            case "setMaxRows":
            case "setLargeMaxRows":
                long value = ((Number) args[0]).longValue();
                if (value < 0) throw new SQLException("Maximum rows must not be negative");
                maxRows = value;
                return null;
            case "getMaxRows":
                return (int) Math.min(maxRows, Integer.MAX_VALUE);
            case "getLargeMaxRows":
                return maxRows;
            case "getMoreResults":
                int mode =
                        args == null || args.length == 0 ? java.sql.Statement.CLOSE_CURRENT_RESULT : (Integer) args[0];
                if (mode == java.sql.Statement.KEEP_CURRENT_RESULT) {
                    throw new SQLFeatureNotSupportedException("Keeping multiple results is not supported");
                }
                if (mode != java.sql.Statement.CLOSE_CURRENT_RESULT && mode != java.sql.Statement.CLOSE_ALL_RESULTS) {
                    throw new SQLException("Invalid result closing mode");
                }
                if (resultSet != null) resultSet.close();
                resultSet = null;
                return false;
            case "getWarnings":
                return null;
            case "clearWarnings":
                return null;
            case "getFetchDirection":
                return ResultSet.FETCH_FORWARD;
            case "setFetchDirection":
                if (((Integer) args[0]) != ResultSet.FETCH_FORWARD)
                    throw new SQLFeatureNotSupportedException("Only forward fetch is supported");
                return null;
            case "setFetchSize":
                return null;
            case "getFetchSize":
                return 0;
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

    private ResultSet execute(String sql) throws SQLException {
        if (resultSet != null) resultSet.close();
        resultSet = null;
        SqlQuery query = SqlQuery.parse(sql, connection.getCatalog());
        SearchResult result;
        if (query.isConnectionProbe()) {
            result = new SearchResult(query.columns, Collections.singletonList(query.probeRow));
        } else {
            if (maxRows > 0) query.body.put("size", (int) Math.min(maxRows, (Integer) query.body.get("size")));
            result = client.search(query);
        }
        if (maxRows > 0 && result.rows.size() > maxRows) {
            result = new SearchResult(result.columns, new ArrayList<>(result.rows.subList(0, (int) maxRows)));
        }
        return (ResultSet) Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                new EsResultSetHandler(result));
    }

    private static void requireSql(Object[] args) throws SQLException {
        if (args == null || args.length == 0 || !(args[0] instanceof String))
            throw new SQLException("SQL text is required");
    }

    private void checkOpen() throws SQLException {
        if (closed || connection.isClosed()) throw new SQLException("Statement or connection is closed");
    }
}
