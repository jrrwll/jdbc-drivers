package org.dreamcat.elasticsearch.jdbc;

import org.dreamcat.elasticsearch.client.EsSearchClient;
import org.dreamcat.elasticsearch.client.EsUpdateClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A PreparedStatement that binds parameters into supported SQL before execution. */
final class EsPreparedStatementHandler implements InvocationHandler {

    private final Connection connection;
    private final EsStatementExecutor executor;
    private final String sql;
    private final Map<Integer, Object> parameters = new LinkedHashMap<>();
    private final List<String> batch = new ArrayList<>();
    private boolean closed;

    EsPreparedStatementHandler(Connection connection, EsSearchClient searchClient, EsUpdateClient updateClient,
            String sql) {
        this.connection = connection;
        this.executor = new EsStatementExecutor(connection, searchClient, updateClient);
        this.sql = sql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ESPreparedStatement");
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
            case "execute":
                return executor.execute(render());
            case "executeQuery":
                return executor.executeQuery(render());
            case "executeUpdate":
                return (int) executor.executeUpdate(render());
            case "executeLargeUpdate":
                return executor.executeUpdate(render());
            case "getResultSet":
                return executor.getResultSet();
            case "getConnection":
                return connection;
            case "getUpdateCount":
                return (int) Math.min(executor.getUpdateCount(), Integer.MAX_VALUE);
            case "getLargeUpdateCount":
                return executor.getUpdateCount();
            case "getResultSetType":
                return ResultSet.TYPE_FORWARD_ONLY;
            case "getResultSetConcurrency":
                return ResultSet.CONCUR_READ_ONLY;
            case "getResultSetHoldability":
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getMoreResults":
                int mode = args == null || args.length == 0 ? Statement.CLOSE_CURRENT_RESULT : (Integer) args[0];
                return executor.getMoreResults(mode);
            case "setMaxRows":
            case "setLargeMaxRows":
                long max = ((Number) args[0]).longValue();
                if (max < 0) throw new SQLException("Maximum rows must not be negative");
                executor.setMaxRows(max);
                return null;
            case "getMaxRows":
                return (int) Math.min(executor.getMaxRows(), Integer.MAX_VALUE);
            case "getLargeMaxRows":
                return executor.getMaxRows();
            case "clearParameters":
                parameters.clear();
                return null;
            case "setNull":
                setParameter((Integer) args[0], null);
                return null;
            case "getParameterMetaData":
                return parameterMetaData(proxy);
            case "addBatch":
                if (args != null && args.length > 0)
                    throw new SQLFeatureNotSupportedException("addBatch(String) is not supported");
                batch.add(render());
                return null;
            case "clearBatch":
                batch.clear();
                return null;
            case "executeBatch":
                return executeBatch(false);
            case "executeLargeBatch":
                return executeBatch(true);
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
                break;
        }
        if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
            setParameter((Integer) args[0], args[1]);
            return null;
        }
        throw JdbcSupport.unsupported(method);
    }

    private void setParameter(int index, Object value) throws SQLException {
        if (index < 1) throw new SQLException("Parameter index must be positive: " + index);
        parameters.put(index, value);
    }

    private String render() throws SQLException {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        boolean single = false;
        boolean quoted = false;
        int index = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (single) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') out.append(sql.charAt(++i));
                    else single = false;
                }
            } else if (quoted) {
                out.append(c);
                if (c == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') out.append(sql.charAt(++i));
                    else quoted = false;
                }
            } else if (c == '\'') {
                single = true;
                out.append(c);
            } else if (c == '"') {
                quoted = true;
                out.append(c);
            } else if (c == '?') {
                out.append(literalSql(parameters.get(++index)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String literalSql(Object value) throws SQLException {
        if (value == null) return "NULL";
        if (value instanceof String || value instanceof Character) return quote(value.toString());
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date || value instanceof java.sql.Time) {
            return quote(value.toString());
        }
        if (value instanceof java.util.Date) {
            return quote(new java.sql.Timestamp(((java.util.Date) value).getTime()).toString());
        }
        throw new SQLException("Unsupported parameter type: " + value.getClass().getName());
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private Object executeBatch(boolean large) throws SQLException {
        long[] counts = new long[batch.size()];
        int completed = 0;
        try {
            for (String statement : batch) {
                counts[completed] = executor.executeUpdate(statement);
                completed++;
            }
        } catch (SQLException e) {
            long[] partial = new long[completed];
            System.arraycopy(counts, 0, partial, 0, completed);
            throw new BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(), partial, e);
        } finally {
            batch.clear();
        }
        if (large) return counts;
        int[] result = new int[counts.length];
        for (int i = 0; i < counts.length; i++) result[i] = (int) counts[i];
        return result;
    }

    private Object parameterMetaData(Object proxy) {
        return Proxy.newProxyInstance(EsPreparedStatementHandler.class.getClassLoader(),
                new Class<?>[]{ParameterMetaData.class},
                (metaProxy, method, args) -> {
                    switch (method.getName()) {
                        case "getParameterCount":
                            return countParameters();
                        case "getParameterMode":
                            return ParameterMetaData.parameterModeIn;
                        case "getParameterType":
                            return Types.OTHER;
                        case "getParameterTypeName":
                            return "unknown";
                        case "getParameterClassName":
                            return Object.class.getName();
                        case "getPrecision":
                        case "getScale":
                            return 0;
                        case "isNullable":
                            return ParameterMetaData.parameterNullableUnknown;
                        case "isSigned":
                            return false;
                        case "unwrap":
                            if (((Class<?>) args[0]).isInstance(metaProxy)) return metaProxy;
                            throw new SQLException("Not a wrapper for " + args[0]);
                        case "isWrapperFor":
                            return ((Class<?>) args[0]).isInstance(metaProxy);
                        case "toString":
                            return "ESParameterMetaData";
                        case "hashCode":
                            return System.identityHashCode(metaProxy);
                        case "equals":
                            return metaProxy == args[0];
                        default:
                            throw JdbcSupport.unsupported(method);
                    }
                });
    }

    private int countParameters() {
        int count = 0;
        boolean single = false;
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (single) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i++;
                    else single = false;
                }
            } else if (quoted) {
                if (c == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') i++;
                    else quoted = false;
                }
            } else if (c == '\'') {
                single = true;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '?') {
                count++;
            }
        }
        return count;
    }

    private void checkOpen() throws SQLException {
        if (closed || connection.isClosed()) throw new SQLException("Statement or connection is closed");
    }
}
