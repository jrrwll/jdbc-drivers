package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SqlQuery implements SqlCommand {

    static final int DEFAULT_LIMIT = 500;

    public final String index;
    public final boolean allColumns;
    public final List<String> columns;
    public final Map<String, Object> body;
    public final Map<String, Object> probeRow;

    private SqlQuery(String index, boolean allColumns, List<String> columns, Map<String, Object> body,
            Map<String, Object> probeRow) {
        this.index = index;
        this.allColumns = allColumns;
        this.columns = columns;
        this.body = body;
        this.probeRow = probeRow;
    }

    static SqlQuery parse(String sql) throws SQLException {
        return parse(sql, null);
    }

    static SqlQuery parse(String sql, String catalog) throws SQLException {
        Statement statement = SqlSupport.parse(sql);
        if (!(statement instanceof Select)) throw new SQLException("Only SELECT statements are supported");
        return from((Select) statement, catalog);
    }

    static SqlQuery from(Select select, String catalog) throws SQLException {
        if (!(select instanceof PlainSelect)) throw new SQLException("Only a single SELECT is supported");
        PlainSelect plain = (PlainSelect) select;
        if (plain.getDistinct() != null || plain.getGroupBy() != null || plain.getHaving() != null) {
            throw new SQLException("DISTINCT, GROUP BY and HAVING are not supported");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()
                || select.getFetch() != null || select.getLimitBy() != null || select.getForClause() != null
                || plain.getTop() != null || plain.getSkip() != null || plain.getFirst() != null
                || plain.getForMode() != null || plain.getQualify() != null
                || plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()
                || plain.getIntoTempTable() != null) {
            throw new SQLException("Unsupported SELECT clause");
        }
        if (plain.getJoins() != null && !plain.getJoins().isEmpty()) throw new SQLException("JOIN is not supported");
        FromItem from = plain.getFromItem();
        if (from == null) return connectionProbe(select, plain);
        if (!(from instanceof Table)) throw new SQLException("A single index is required in FROM");
        Table table = (Table) from;
        String index = table.getName() == null ? null : SqlSupport.identifier(table.getName());
        if (table.getNameParts().size() > 2 || table.getPivot() != null || table.getUnPivot() != null
                || index == null || !index.matches("[A-Za-z0-9._-]+")) {
            throw new SQLException("Only a plain single index is supported");
        }
        if (table.getSchemaName() != null && (catalog == null
                || !SqlSupport.identifier(table.getSchemaName()).equals(catalog))) {
            throw new SQLException("Unknown catalog: " + table.getSchemaName());
        }
        String alias = table.getAlias() == null ? null : SqlSupport.identifier(table.getAlias().getName());
        if (alias != null && !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SQLException("Invalid table alias");
        }
        if (index.isEmpty() || ".".equals(index) || "..".equals(index)) throw new SQLException("Invalid index name");

        List<String> columns = new ArrayList<>();
        boolean allColumns = false;
        for (SelectItem<?> item : plain.getSelectItems()) {
            if (item.getAlias() != null) throw new SQLException("Column aliases are not supported");
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns) {
                if (plain.getSelectItems().size() != 1) throw new SQLException("* cannot be combined with columns");
                String qualifier = SqlSupport.identifier(((AllTableColumns) expression).getTable().getName());
                if (!qualifier.equals(alias == null ? index : alias))
                    throw new SQLException("Unknown table qualifier: " + qualifier);
                allColumns = true;
            } else if (expression instanceof AllColumns) {
                if (plain.getSelectItems().size() != 1) throw new SQLException("* cannot be combined with columns");
                allColumns = true;
            } else if (expression instanceof Column) {
                columns.add(SqlSupport.field((Column) expression, alias));
            } else {
                throw new SQLException("Only column names or * are supported in SELECT");
            }
        }
        if (!allColumns && columns.isEmpty()) throw new SQLException("At least one column is required");

        Map<String, Object> body = new LinkedHashMap<>();
        List<String> sourceColumns = new ArrayList<>(columns);
        sourceColumns.remove("_id");
        body.put("_source", allColumns ? true : sourceColumns);
        body.put("query", plain.getWhere() == null
                ? SqlSupport.map("match_all", Collections.emptyMap())
                : toQuery(plain.getWhere(), alias));
        if (select.getOrderByElements() != null && !select.getOrderByElements().isEmpty()) {
            List<Object> sorts = new ArrayList<>();
            for (OrderByElement order : select.getOrderByElements()) {
                if (!(order.getExpression() instanceof Column))
                    throw new SQLException("ORDER BY only supports columns");
                Map<String, Object> sort = new LinkedHashMap<>();
                sort.put(SqlSupport.field((Column) order.getExpression(), alias), order.isAsc() ? "asc" : "desc");
                sorts.add(sort);
            }
            body.put("sort", sorts);
        }
        int offset = 0;
        int limit = DEFAULT_LIMIT;
        Limit limitNode = select.getLimit();
        if (limitNode != null) {
            if (limitNode.isLimitAll() || limitNode.getRowCount() == null)
                throw new SQLException("LIMIT ALL is not supported");
            limit = SqlSupport.nonNegativeInt(limitNode.getRowCount(), "LIMIT");
            if (limitNode.getOffset() != null) offset = SqlSupport.nonNegativeInt(limitNode.getOffset(), "OFFSET");
        }
        if (select.getOffset() != null) offset = SqlSupport.nonNegativeInt(select.getOffset().getOffset(), "OFFSET");
        body.put("from", offset);
        body.put("size", limit);
        return new SqlQuery(index, allColumns, columns, body, null);
    }

    public boolean isConnectionProbe() {
        return index == null;
    }

    private static SqlQuery connectionProbe(Select select, PlainSelect plain) throws SQLException {
        if (plain.getWhere() != null || select.getOrderByElements() != null || select.getOffset() != null
                || select.getLimit() != null && (select.getLimit().getOffset() != null
                || select.getLimit().isLimitAll() || select.getLimit().getRowCount() == null
                || SqlSupport.nonNegativeInt(select.getLimit().getRowCount(), "LIMIT") != 1)) {
            throw new SQLException("Only literal connection probes are supported without FROM");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            Object value;
            try {
                value = SqlSupport.literal(expression);
            } catch (SQLException e) {
                throw new SQLException("Only literal connection probes are supported without FROM", e);
            }
            String label = item.getAlias() == null ? expression.toString() : item.getAlias().getName();
            if (row.containsKey(label)) throw new SQLException("Duplicate probe column: " + label);
            row.put(label, value);
        }
        if (row.isEmpty()) throw new SQLException("Only literal connection probes are supported without FROM");
        return new SqlQuery(null, false, new ArrayList<>(row.keySet()), Collections.emptyMap(), row);
    }

    private static Map<String, Object> toQuery(Expression expression, String alias) throws SQLException {
        if (expression instanceof Parenthesis) return toQuery(((Parenthesis) expression).getExpression(), alias);
        if (expression instanceof AndExpression)
            return bool("must", toQuery(((AndExpression) expression).getLeftExpression(), alias),
                    toQuery(((AndExpression) expression).getRightExpression(), alias));
        if (expression instanceof OrExpression) {
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("should", Arrays.asList(toQuery(((OrExpression) expression).getLeftExpression(), alias),
                    toQuery(((OrExpression) expression).getRightExpression(), alias)));
            bool.put("minimum_should_match", 1);
            return SqlSupport.map("bool", bool);
        }
        if (expression instanceof LikeExpression) return like((LikeExpression) expression, alias);
        if (expression instanceof Between) return between((Between) expression, alias);
        if (expression instanceof InExpression) return in((InExpression) expression, alias);
        if (expression instanceof IsNullExpression) return isNull((IsNullExpression) expression, alias);
        if (expression instanceof BinaryExpression) return comparison((BinaryExpression) expression, alias);
        throw unsupportedExpression(expression);
    }

    private static Map<String, Object> comparison(BinaryExpression expression, String alias) throws SQLException {
        if (!(expression.getLeftExpression() instanceof Column))
            throw new SQLException("Left side of a condition must be a column");
        String operator = expression.getStringExpression();
        String field = SqlSupport.field((Column) expression.getLeftExpression(), alias);
        Object value = SqlSupport.literal(expression.getRightExpression());
        if ("=".equals(operator)) return SqlSupport.map("term", SqlSupport.map(field, value));
        if ("!=".equals(operator) || "<>".equals(operator))
            return notExistingSafe(field, SqlSupport.map("term", SqlSupport.map(field, value)));
        String rangeOperator;
        if (">".equals(operator)) rangeOperator = "gt";
        else if (">=".equals(operator)) rangeOperator = "gte";
        else if ("<".equals(operator)) rangeOperator = "lt";
        else if ("<=".equals(operator)) rangeOperator = "lte";
        else throw new SQLException("Unsupported operator: " + operator);
        return SqlSupport.map("range", SqlSupport.map(field, SqlSupport.map(rangeOperator, value)));
    }

    private static Map<String, Object> like(LikeExpression expression, String alias) throws SQLException {
        if (!(expression.getLeftExpression() instanceof Column))
            throw new SQLException("LIKE left side must be a column");
        if (expression.getEscape() != null || expression.isCaseInsensitive())
            throw new SQLException("LIKE ESCAPE and ILIKE are not supported");
        Object value = SqlSupport.literal(expression.getRightExpression());
        if (!(value instanceof String)) throw new SQLException("LIKE pattern must be a string");
        StringBuilder wildcard = new StringBuilder();
        for (char c : ((String) value).toCharArray()) {
            if (c == '%') wildcard.append('*');
            else if (c == '_') wildcard.append('?');
            else {
                if (c == '*' || c == '?' || c == '\\') wildcard.append('\\');
                wildcard.append(c);
            }
        }
        String field = SqlSupport.field((Column) expression.getLeftExpression(), alias);
        Map<String, Object> clause = SqlSupport.map("wildcard", SqlSupport.map(field, wildcard.toString()));
        return expression.isNot() ? notExistingSafe(field, clause) : clause;
    }

    private static Map<String, Object> between(Between expression, String alias) throws SQLException {
        if (!(expression.getLeftExpression() instanceof Column))
            throw new SQLException("BETWEEN left side must be a column");
        String field = SqlSupport.field((Column) expression.getLeftExpression(), alias);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("gte", SqlSupport.literal(expression.getBetweenExpressionStart()));
        range.put("lte", SqlSupport.literal(expression.getBetweenExpressionEnd()));
        Map<String, Object> clause = SqlSupport.map("range", SqlSupport.map(field, range));
        return expression.isNot() ? notExistingSafe(field, clause) : clause;
    }

    private static Map<String, Object> in(InExpression expression, String alias) throws SQLException {
        if (!(expression.getLeftExpression() instanceof Column)
                || !(expression.getRightExpression() instanceof ExpressionList)) {
            throw new SQLException("IN requires a column and a literal list");
        }
        List<Object> values = new ArrayList<>();
        for (Object item : ((ExpressionList<?>) expression.getRightExpression()).getExpressions())
            values.add(SqlSupport.literal((Expression) item));
        String field = SqlSupport.field((Column) expression.getLeftExpression(), alias);
        Map<String, Object> clause = SqlSupport.map("terms", SqlSupport.map(field, values));
        return expression.isNot() ? notExistingSafe(field, clause) : clause;
    }

    private static Map<String, Object> isNull(IsNullExpression expression, String alias) throws SQLException {
        if (!(expression.getLeftExpression() instanceof Column))
            throw new SQLException("IS NULL left side must be a column");
        Map<String, Object> exists = SqlSupport.map("exists",
                SqlSupport.map("field", SqlSupport.field((Column) expression.getLeftExpression(), alias)));
        return expression.isNot() ? exists
                : SqlSupport.map("bool", SqlSupport.map("must_not", Collections.singletonList(exists)));
    }

    private static Map<String, Object> bool(String key, Map<String, Object> left, Map<String, Object> right) {
        return SqlSupport.map("bool", SqlSupport.map(key, Arrays.asList(left, right)));
    }

    private static Map<String, Object> notExistingSafe(String field, Map<String, Object> clause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("must", Collections.singletonList(SqlSupport.map("exists", SqlSupport.map("field", field))));
        body.put("must_not", Collections.singletonList(clause));
        return SqlSupport.map("bool", body);
    }

    private static SQLException unsupportedExpression(Expression expression) {
        return new SQLException("Unsupported WHERE expression: " + expression);
    }
}
