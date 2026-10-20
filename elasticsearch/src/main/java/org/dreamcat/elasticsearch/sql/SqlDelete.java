package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.delete.Delete;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** DELETE by a finite set of document IDs. Other conjunctive equalities are warned about and ignored. */
public final class SqlDelete implements SqlCommand {

    public final String index;
    public final List<String> ids;
    public final List<String> ignoredFields;

    private SqlDelete(String index, Set<String> ids, Set<String> ignoredFields) {
        this.index = index;
        this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
        this.ignoredFields = Collections.unmodifiableList(new ArrayList<>(ignoredFields));
    }

    static SqlDelete from(Delete delete, String catalog) throws SQLException {
        if (delete.getWithItemsList() != null && !delete.getWithItemsList().isEmpty()
                || delete.getTables() != null && !delete.getTables().isEmpty()
                || delete.getUsingList() != null && !delete.getUsingList().isEmpty()
                || delete.getJoins() != null && !delete.getJoins().isEmpty()
                || delete.getLimit() != null
                || delete.getOrderByElements() != null && !delete.getOrderByElements().isEmpty()
                || delete.getReturningClause() != null || delete.getOutputClause() != null
                || delete.isModifierIgnore() || delete.isModifierQuick() || delete.getModifierPriority() != null) {
            throw new SQLException("Only a single-table DELETE by _id is supported");
        }
        String index = SqlSupport.writeIndex(delete.getTable(), catalog);
        String qualifier = SqlSupport.writeQualifier(delete.getTable(), index);
        Set<String> ignored = new LinkedHashSet<>();
        Set<String> ids = primaryKeys(delete.getWhere(), qualifier, ignored, true);
        if (ids == null) throw new SQLException("An _id equality or _id IN condition is required in DELETE WHERE");
        return new SqlDelete(index, ids, ignored);
    }

    // A null set represents an ignored non-ID equality; an empty set represents contradictory ID conditions.
    private static Set<String> primaryKeys(Expression expression, String qualifier, Set<String> ignored,
            boolean allowIgnored) throws SQLException {
        expression = unwrap(expression);
        if (expression instanceof AndExpression) {
            AndExpression and = (AndExpression) expression;
            Set<String> left = primaryKeys(and.getLeftExpression(), qualifier, ignored, allowIgnored);
            Set<String> right = primaryKeys(and.getRightExpression(), qualifier, ignored, allowIgnored);
            if (left == null) return right;
            if (right != null) left.retainAll(right);
            return left;
        }
        if (expression instanceof OrExpression) {
            OrExpression or = (OrExpression) expression;
            // An OR branch containing ignored fields could select documents outside the supplied IDs.
            Set<String> left = primaryKeys(or.getLeftExpression(), qualifier, ignored, false);
            left.addAll(primaryKeys(or.getRightExpression(), qualifier, ignored, false));
            return left;
        }
        Set<String> ids = new LinkedHashSet<>();
        if (expression instanceof EqualsTo) {
            EqualsTo equality = (EqualsTo) expression;
            if (equality.getOldOracleJoinSyntax() != 0 || equality.getOraclePriorPosition() != 0)
                throw unsupported();
            Expression column = unwrap(equality.getLeftExpression());
            Expression value = unwrap(equality.getRightExpression());
            if (!(column instanceof Column) && value instanceof Column) {
                Expression swap = column;
                column = value;
                value = swap;
            }
            if (!(column instanceof Column)) throw unsupported();
            String field = SqlSupport.field((Column) column, qualifier);
            if ("_id".equals(field)) ids.add(SqlSupport.documentId(value));
            else {
                if (!allowIgnored) throw unsupported();
                SqlSupport.literal(value);
                ignored.add(field);
                return null;
            }
            return ids;
        }
        if (expression instanceof InExpression) {
            InExpression in = (InExpression) expression;
            Expression column = unwrap(in.getLeftExpression());
            if (in.isNot() || in.isGlobal() || in.getOldOracleJoinSyntax() != 0
                    || in.getOraclePriorPosition() != 0 || !(column instanceof Column)
                    || !"_id".equals(SqlSupport.field((Column) column, qualifier))
                    || !(in.getRightExpression() instanceof ExpressionList)) throw unsupported();
            ExpressionList<?> values = (ExpressionList<?>) in.getRightExpression();
            if (values.isEmpty()) throw unsupported();
            for (Expression value : values) ids.add(SqlSupport.documentId(unwrap(value)));
            return ids;
        }
        throw unsupported();
    }

    private static Expression unwrap(Expression expression) {
        while (expression instanceof Parenthesis) expression = ((Parenthesis) expression).getExpression();
        return expression;
    }

    private static SQLException unsupported() {
        return new SQLException(
                "DELETE only supports _id equality/IN conditions and additional AND field = literal conditions");
    }
}
