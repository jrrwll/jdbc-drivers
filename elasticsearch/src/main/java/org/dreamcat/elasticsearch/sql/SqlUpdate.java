package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An UPDATE by primary key (_id) translated into an Elasticsearch _update request. */
public final class SqlUpdate implements SqlCommand {

    public final String index;
    public final String id;
    public final Map<String, Object> doc;

    private SqlUpdate(String index, String id, Map<String, Object> doc) {
        this.index = index;
        this.id = id;
        this.doc = doc;
    }

    static SqlUpdate parse(String sql, String catalog) throws SQLException {
        Statement statement = SqlSupport.parse(sql);
        if (!(statement instanceof Update)) throw new SQLException("Only UPDATE statements are supported");
        return from((Update) statement, catalog);
    }

    static SqlUpdate from(Update update, String catalog) throws SQLException {
        if (update.getWithItemsList() != null && !update.getWithItemsList().isEmpty()
                || update.getFromItem() != null
                || update.getJoins() != null && !update.getJoins().isEmpty()
                || update.getStartJoins() != null && !update.getStartJoins().isEmpty()
                || update.getSelect() != null || update.isUseSelect()
                || update.getLimit() != null
                || update.getOrderByElements() != null && !update.getOrderByElements().isEmpty()
                || update.getReturningClause() != null || update.getOutputClause() != null) {
            throw new SQLException("Only a single-table UPDATE is supported");
        }
        Table table = update.getTable();
        String index = table == null || table.getName() == null ? null
                : SqlSupport.identifier(table.getName());
        if (table == null || table.getPivot() != null || table.getUnPivot() != null
                || table.getNameParts().size() > 2 || index == null || !index.matches("[A-Za-z0-9._-]+")) {
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

        String qualifier = alias == null ? index : alias;
        Map<String, Object> doc = assignments(update, qualifier);
        String id = primaryKey(update.getWhere(), qualifier);
        return new SqlUpdate(index, id, doc);
    }

    private static Map<String, Object> assignments(Update update, String alias) throws SQLException {
        List<UpdateSet> sets = update.getUpdateSets();
        if (sets == null || sets.isEmpty()) throw new SQLException("SET is required");
        Map<String, Object> doc = new LinkedHashMap<>();
        for (UpdateSet set : sets) {
            if (set.getColumns() == null || set.getValues() == null
                    || set.getColumns().size() != 1 || set.getValues().size() != 1
                    || set.getColumns().isUsingBrackets()) {
                throw new SQLException("Only single-column assignments are supported in SET");
            }
            String field = SqlSupport.field(set.getColumn(0), alias);
            if ("_id".equals(field)) throw new SQLException("The primary key _id cannot be updated");
            if (doc.containsKey(field)) throw new SQLException("Duplicate column in SET: " + field);
            Expression value = set.getValue(0);
            doc.put(field, value instanceof NullValue ? null : SqlSupport.literal(value));
        }
        return doc;
    }

    // Non-primary-key equality conditions are accepted but ignored; exactly one _id equality is required.
    private static String primaryKey(Expression where, String alias) throws SQLException {
        List<Expression> conditions = new ArrayList<>();
        collectConditions(where, conditions);
        String id = null;
        for (Expression condition : conditions) {
            if (!(condition instanceof EqualsTo) || !(((EqualsTo) condition).getLeftExpression() instanceof Column)) {
                throw new SQLException("Only equality conditions are supported in WHERE");
            }
            String field = SqlSupport.field((Column) ((EqualsTo) condition).getLeftExpression(), alias);
            if (!"_id".equals(field)) continue;
            if (id != null)
                throw new SQLException("Only one primary key condition _id = <value> is supported in WHERE");
            id = String.valueOf(SqlSupport.literal(((EqualsTo) condition).getRightExpression()));
        }
        if (id == null) throw new SQLException("A primary key condition _id = <value> is required in WHERE");
        return id;
    }

    private static void collectConditions(Expression expression, List<Expression> conditions) {
        while (expression instanceof Parenthesis) expression = ((Parenthesis) expression).getExpression();
        if (expression instanceof AndExpression) {
            collectConditions(((AndExpression) expression).getLeftExpression(), conditions);
            collectConditions(((AndExpression) expression).getRightExpression(), conditions);
        } else {
            conditions.add(expression);
        }
    }
}
