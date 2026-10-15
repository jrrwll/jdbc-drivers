package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parsing and value helpers shared by the SELECT and UPDATE statement translators. */
public final class SqlSupport {

    private SqlSupport() {
    }

    static Statement parse(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) throw new SQLException("SQL must not be empty");
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new SQLException("Invalid SQL", e);
        }
    }

    static Object literal(Expression expression) throws SQLException {
        if (expression instanceof StringValue) return ((StringValue) expression).getValue();
        if (expression instanceof LongValue) return ((LongValue) expression).getValue();
        if (expression instanceof DoubleValue) return ((DoubleValue) expression).getValue();
        if (expression instanceof Column && ((Column) expression).getTable() == null) {
            String name = ((Column) expression).getColumnName();
            if ("true".equalsIgnoreCase(name)) return true;
            if ("false".equalsIgnoreCase(name)) return false;
        }
        if (expression instanceof DateValue) return ((DateValue) expression).getValue().toString();
        if (expression instanceof TimeValue) return ((TimeValue) expression).getValue().toString();
        if (expression instanceof TimestampValue) return ((TimestampValue) expression).getValue().toString();
        if (expression instanceof SignedExpression) {
            Object value = literal(((SignedExpression) expression).getExpression());
            if (((SignedExpression) expression).getSign() == '-') {
                if (value instanceof Long) return -((Long) value);
                if (value instanceof Double) return -((Double) value);
                if (value instanceof BigDecimal) return ((BigDecimal) value).negate();
            }
            return value;
        }
        if (expression instanceof NullValue || expression instanceof JdbcParameter || expression instanceof Function) {
            throw new SQLException("Only literal values are supported in conditions");
        }
        throw new SQLException("Unsupported literal: " + expression);
    }

    static String field(Column column, String alias) throws SQLException {
        String name = column.getColumnName() == null ? null : identifier(column.getColumnName());
        if (name == null || name.isEmpty()) throw new SQLException("Column name is required");
        Table table = column.getTable();
        if (table == null || table.getName() == null || table.getName().isEmpty()) return name;
        String qualifier = identifier(table.getName());
        if (alias != null && alias.equals(qualifier)) return name;
        return qualifier + "." + name;
    }

    static String identifier(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == last && (first == '"' || first == '`')) {
                String escape = new String(new char[]{first, first});
                return value.substring(1, value.length() - 1).replace(escape, String.valueOf(first));
            }
        }
        return value;
    }

    static int nonNegativeInt(Expression expression, String name) throws SQLException {
        Object value = literal(expression);
        if (!(value instanceof Long)) throw new SQLException(name + " must be an integer");
        long number = (Long) value;
        if (number < 0 || number > Integer.MAX_VALUE) throw new SQLException(name + " is out of range");
        return (int) number;
    }

    public static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
