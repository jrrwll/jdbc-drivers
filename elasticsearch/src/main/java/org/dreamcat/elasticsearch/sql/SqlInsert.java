package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Literal VALUES rows; _id is metadata and is never included in the document source. */
public final class SqlInsert implements SqlCommand {

    public final String index;
    public final List<Row> rows;

    private SqlInsert(String index, List<Row> rows) {
        this.index = index;
        this.rows = Collections.unmodifiableList(rows);
    }

    static SqlInsert from(Insert insert, String catalog) throws SQLException {
        if (!(insert.getSelect() instanceof Values)
                || insert.getWithItemsList() != null && !insert.getWithItemsList().isEmpty()
                || insert.getDuplicateUpdateSets() != null && !insert.getDuplicateUpdateSets().isEmpty()
                || insert.getSetUpdateSets() != null && !insert.getSetUpdateSets().isEmpty()
                || insert.getConflictAction() != null || insert.getConflictTarget() != null
                || insert.getReturningClause() != null || insert.getOutputClause() != null
                || insert.isModifierIgnore() || insert.getModifierPriority() != null) {
            throw new SQLException("Only INSERT INTO ... (columns) VALUES (...) is supported");
        }
        Values values = (Values) insert.getSelect();
        if (values.getLimit() != null || values.getLimitBy() != null || values.getOffset() != null
                || values.getFetch() != null || values.getForClause() != null || values.getIsolation() != null
                || values.getOrderByElements() != null && !values.getOrderByElements().isEmpty()
                || values.getWithItemsList() != null && !values.getWithItemsList().isEmpty()) {
            throw new SQLException("INSERT VALUES modifiers are not supported");
        }
        String index = SqlSupport.writeIndex(insert.getTable(), catalog);
        String qualifier = SqlSupport.writeQualifier(insert.getTable(), index);
        if (insert.getColumns() == null || insert.getColumns().isEmpty())
            throw new SQLException("An explicit column list is required for INSERT");
        List<String> columns = new ArrayList<>();
        for (Column column : insert.getColumns()) {
            String field = SqlSupport.field(column, qualifier);
            if (columns.contains(field)) throw new SQLException("Duplicate INSERT column: " + field);
            columns.add(field);
        }
        ExpressionList<?> expressions = values.getExpressions();
        if (expressions == null || expressions.isEmpty()) throw new SQLException("VALUES rows are required");
        List<Row> rows = new ArrayList<>();
        // Single-column rows are Parenthesis nodes; wider rows are expression lists.
        if (expressions.get(0) instanceof ExpressionList) {
            for (Expression expression : expressions) {
                if (!(expression instanceof ExpressionList)) throw new SQLException("Invalid VALUES row");
                rows.add(row(columns, (ExpressionList<?>) expression));
            }
        } else if (expressions.get(0) instanceof Parenthesis) {
            for (Expression expression : expressions) {
                if (!(expression instanceof Parenthesis)) throw new SQLException("Invalid VALUES row");
                rows.add(row(columns, new ExpressionList<>(((Parenthesis) expression).getExpression())));
            }
        } else {
            rows.add(row(columns, expressions));
        }
        return new SqlInsert(index, rows);
    }

    private static Row row(List<String> columns, ExpressionList<?> values) throws SQLException {
        if (columns.size() != values.size()) throw new SQLException("INSERT column and value counts must match");
        String id = null;
        Map<String, Object> doc = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String field = columns.get(i);
            Expression value = values.get(i);
            if ("_id".equals(field)) id = SqlSupport.documentId(value);
            else doc.put(field, value instanceof NullValue ? null : SqlSupport.literal(value));
        }
        return new Row(id, doc);
    }

    public static final class Row {

        public final String id;
        public final Map<String, Object> doc;

        private Row(String id, Map<String, Object> doc) {
            this.id = id;
            this.doc = Collections.unmodifiableMap(doc);
        }
    }
}
