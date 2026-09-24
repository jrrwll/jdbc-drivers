package org.dreamcat.elasticsearch;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

final class
EsResultSetMetaDataHandler implements InvocationHandler {

    private final SearchResult result;

    EsResultSetMetaDataHandler(SearchResult result) {
        this.result = result;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ES7ResultSetMetaData");
        int index;
        switch (method.getName()) {
            case "getColumnCount":
                return result.columns.size();
            case "getColumnName":
            case "getColumnLabel":
                index = check(args);
                return result.columns.get(index - 1);
            case "getColumnType":
                index = check(args);
                return sqlType(index - 1);
            case "getColumnTypeName":
                index = check(args);
                return typeName(index - 1);
            case "getColumnClassName":
                index = check(args);
                return className(index - 1);
            case "isNullable":
                return ResultSetMetaData.columnNullable;
            case "isReadOnly":
                return true;
            case "isWritable":
            case "isDefinitelyWritable":
                return false;
            case "getTableName":
            case "getSchemaName":
            case "getCatalogName":
                return "";
            case "isAutoIncrement":
            case "isCaseSensitive":
            case "isSearchable":
            case "isCurrency":
            case "isSigned":
                return false;
            case "getColumnDisplaySize":
                return 0;
            case "getPrecision":
            case "getScale":
                return 0;
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                throw new SQLException("Not a wrapper for " + args[0]);
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                throw JdbcSupport.unsupported(method);
        }
    }

    private int check(Object[] args) throws SQLException {
        int index = (Integer) args[0];
        if (index < 1 || index > result.columns.size()) throw new SQLException("Invalid column index: " + index);
        return index;
    }

    private int sqlType(int index) {
        Object value = firstValue(index);
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof Long) return Types.BIGINT;
        if (value instanceof Float) return Types.FLOAT;
        if (value instanceof Double) return Types.DOUBLE;
        if (value instanceof Number) return Types.NUMERIC;
        if (value instanceof Boolean) return Types.BOOLEAN;
        return Types.VARCHAR;
    }

    private String typeName(int index) {
        Object value = firstValue(index);
        return value == null ? "VARCHAR" : value.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private String className(int index) {
        Object value = firstValue(index);
        return value == null ? String.class.getName() : value.getClass().getName();
    }

    private Object firstValue(int index) {
        for (Map<String, Object> row : result.rows) {
            Object value = row.get(result.columns.get(index));
            if (value != null) return value;
        }
        return null;
    }
}
