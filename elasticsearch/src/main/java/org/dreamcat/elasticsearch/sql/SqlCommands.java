package org.dreamcat.elasticsearch.sql;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.sql.SQLException;

/** Parses a SQL text once and dispatches it to the matching statement translator. */
public final class SqlCommands {

    private SqlCommands() {
    }

    public static SqlCommand parse(String sql, String catalog) throws SQLException {
        Statement statement = SqlSupport.parse(sql);
        if (statement instanceof Select) return SqlQuery.from((Select) statement, catalog);
        if (statement instanceof Update) return SqlUpdate.from((Update) statement, catalog);
        if (statement instanceof Insert) return SqlInsert.from((Insert) statement, catalog);
        if (statement instanceof Delete) return SqlDelete.from((Delete) statement, catalog);
        throw new SQLException("Only SELECT, UPDATE, INSERT and DELETE statements are supported");
    }
}
