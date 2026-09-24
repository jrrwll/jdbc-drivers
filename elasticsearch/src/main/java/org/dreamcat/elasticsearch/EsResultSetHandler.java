package org.dreamcat.elasticsearch;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

final class EsResultSetHandler implements InvocationHandler {

    private final SearchResult result;
    private final ResultSetMetaData metadata;
    private int row = -1;
    private boolean closed;
    private boolean wasNull;

    EsResultSetHandler(SearchResult result) {
        this.result = result;
        this.metadata = (ResultSetMetaData) Proxy.newProxyInstance(Driver.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, new EsResultSetMetaDataHandler(result));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ES7ResultSet");
        switch (method.getName()) {
            case "next":
                checkOpen();
                if (row + 1 < result.rows.size()) {
                    row++;
                    return true;
                }
                row = result.rows.size();
                return false;
            case "close":
                closed = true;
                return null;
            case "isClosed":
                return closed;
            case "wasNull":
                return wasNull;
            case "getMetaData":
                return metadata;
            case "findColumn":
                checkOpen();
                return column((String) args[0]);
            case "getObject":
                checkOpen();
                return getObject(args);
            case "getString":
                return string(value(args));
            case "getBoolean":
                return bool(value(args));
            case "getByte":
                return number(value(args), Byte.class).byteValue();
            case "getShort":
                return number(value(args), Short.class).shortValue();
            case "getInt":
                return number(value(args), Integer.class).intValue();
            case "getLong":
                return number(value(args), Long.class).longValue();
            case "getFloat":
                return number(value(args), Float.class).floatValue();
            case "getDouble":
                return number(value(args), Double.class).doubleValue();
            case "getBigDecimal":
                return decimal(value(args));
            case "getBytes":
                return bytes(value(args));
            case "getDate":
                return date(value(args));
            case "getTime":
                return time(value(args));
            case "getTimestamp":
                return timestamp(value(args));
            case "getCharacterStream": {
                String text = string(value(args));
                return text == null ? null : new java.io.StringReader(text);
            }
            case "getAsciiStream":
            case "getBinaryStream": {
                byte[] data = bytes(value(args));
                return data == null ? null : new java.io.ByteArrayInputStream(data);
            }
            case "getRow":
                return row >= 0 && row < result.rows.size() ? row + 1 : 0;
            case "isBeforeFirst":
                return row < 0 && !result.rows.isEmpty();
            case "isAfterLast":
                return row >= result.rows.size() && !result.rows.isEmpty();
            case "isFirst":
                return row == 0 && !result.rows.isEmpty();
            case "isLast":
                return row == result.rows.size() - 1 && !result.rows.isEmpty();
            case "getType":
                return ResultSet.TYPE_FORWARD_ONLY;
            case "getConcurrency":
                return ResultSet.CONCUR_READ_ONLY;
            case "getFetchDirection":
                return ResultSet.FETCH_FORWARD;
            case "setFetchDirection":
                if (((Integer) args[0]) != ResultSet.FETCH_FORWARD)
                    throw new SQLFeatureNotSupportedException("Only forward fetch is supported");
                return null;
            case "getFetchSize":
                return 0;
            case "setFetchSize":
                return null;
            case "getWarnings":
                return null;
            case "clearWarnings":
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

    private Object getObject(Object[] args) throws SQLException {
        Object value = value(args);
        if (args.length == 2 && args[1] instanceof Class && value != null) return convert(value, (Class<?>) args[1]);
        return value;
    }

    private Object value(Object[] args) throws SQLException {
        checkOpen();
        if (row < 0 || row >= result.rows.size()) throw new SQLException("Cursor is not on a row");
        int index = args[0] instanceof Integer ? (Integer) args[0] : column((String) args[0]);
        if (index < 1 || index > result.columns.size()) throw new SQLException("Invalid column index: " + index);
        Object value = result.rows.get(row).get(result.columns.get(index - 1));
        wasNull = value == null;
        return value;
    }

    private int column(String name) throws SQLException {
        for (int i = 0; i < result.columns.size(); i++) {
            if (result.columns.get(i).equals(name) || result.columns.get(i).equalsIgnoreCase(name)) return i + 1;
        }
        throw new SQLException("Unknown column: " + name);
    }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        return value == null ? false
                : value instanceof Boolean ? (Boolean) value : Boolean.valueOf(String.valueOf(value));
    }

    private static Number number(Object value, Class<?> type) throws SQLException {
        if (value == null) return 0;
        if (value instanceof Number) return (Number) value;
        try {
            String text = String.valueOf(value);
            if (type == Byte.class) return Byte.valueOf(text);
            if (type == Short.class) return Short.valueOf(text);
            if (type == Integer.class) return Integer.valueOf(text);
            if (type == Long.class) return Long.valueOf(text);
            if (type == Float.class) return Float.valueOf(text);
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to number: " + value, e);
        }
    }

    private static BigDecimal decimal(Object value) throws SQLException {
        if (value == null) return null;
        try {
            return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to decimal: " + value, e);
        }
    }

    private static byte[] bytes(Object value) {
        return value == null ? null
                : value instanceof byte[] ? (byte[]) value : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static Date date(Object value) throws SQLException {
        if (value == null) return null;
        try {
            return value instanceof Date ? (Date) value : Date.valueOf(String.valueOf(value).substring(0, 10));
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert value to date: " + value, e);
        }
    }

    private static Time time(Object value) throws SQLException {
        if (value == null) return null;
        try {
            return value instanceof Time ? (Time) value : Time.valueOf(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert value to time: " + value, e);
        }
    }

    private static Timestamp timestamp(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Timestamp) return (Timestamp) value;
        String text = String.valueOf(value);
        try {
            if (text.endsWith("Z")) return Timestamp.from(Instant.parse(text));
            if (text.contains("+") || text.substring(Math.min(10, text.length())).contains("-")) {
                return Timestamp.from(OffsetDateTime.parse(text).toInstant());
            }
            return Timestamp.valueOf(text.replace('T', ' '));
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert value to timestamp: " + value, e);
        }
    }

    private static Object convert(Object value, Class<?> type) throws SQLException {
        if (type.isInstance(value)) return value;
        if (type == String.class) return string(value);
        if (type == Integer.class || type == int.class) return number(value, Integer.class).intValue();
        if (type == Long.class || type == long.class) return number(value, Long.class).longValue();
        if (type == Boolean.class || type == boolean.class) return bool(value);
        if (type == Double.class || type == double.class) return number(value, Double.class).doubleValue();
        throw new SQLException("Cannot convert value to " + type.getName());
    }
}
