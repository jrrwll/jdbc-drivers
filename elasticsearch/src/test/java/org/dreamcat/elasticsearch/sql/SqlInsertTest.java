package org.dreamcat.elasticsearch.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class SqlInsertTest {

    @Test
    void parsesSingleAndMultipleRowsWithOptionalId() throws Exception {
        SqlInsert insert = parse("insert into records (_id, name, score, active, note) "
                + "values ('a/b', 'O''Brien', -3, true, null), (42, '中文', 1.5, false, 'ok')");
        assertEquals("records", insert.index);
        assertEquals(2, insert.rows.size());
        assertEquals("a/b", insert.rows.get(0).id);
        assertEquals("O'Brien", insert.rows.get(0).doc.get("name"));
        assertEquals(-3L, insert.rows.get(0).doc.get("score"));
        assertEquals(Boolean.TRUE, insert.rows.get(0).doc.get("active"));
        assertTrue(insert.rows.get(0).doc.containsKey("note"));
        assertNull(insert.rows.get(0).doc.get("note"));
        assertFalse(insert.rows.get(0).doc.containsKey("_id"));
        assertEquals("42", insert.rows.get(1).id);
        assertEquals(1.5, insert.rows.get(1).doc.get("score"));
        assertEquals(Boolean.FALSE, insert.rows.get(1).doc.get("active"));
        assertNull(parse("insert into records (score) values (7)").rows.get(0).id);
        assertEquals(2, parse("insert into records (score) values (7), (8)").rows.size());
        assertEquals("a", parse("insert into records (_id) values ('a')").rows.get(0).id);
    }

    @Test
    void acceptsQuotedColumnsAndCatalog() throws Exception {
        SqlInsert insert = (SqlInsert) SqlCommands.parse(
                "insert into \"test-cluster\".\"records\" (\"_id\", \"user.name\") values ('a', 'Ada')",
                "test-cluster");
        assertEquals("Ada", insert.rows.get(0).doc.get("user.name"));
        assertEquals("records", insert.index);
    }

    @Test
    void rejectsInvalidRowsAndUnsupportedClauses() {
        String[] examples = {
                "insert into records values (1)",
                "insert into records (a, b) values (1)",
                "insert into records (a) values (1, 2)",
                "insert into records (a) values (1), (2, 3)",
                "insert into records (a, a) values (1, 2)",
                "insert into records (_id) values (null)",
                "insert into records (_id) values ('')",
                "insert into records (a) values (other)",
                "insert into records (a) values (1 + 2)",
                "insert into records (a) values (now())",
                "insert into records (a) values (?)",
                "insert into records (a) select score from other",
                "insert into records (a) values (1) on duplicate key update a = 2",
                "insert into records (a) values (1) on conflict do nothing",
                "insert ignore into records (a) values (1)",
                "insert into records set a = 1",
                "insert into records (a) values (1) returning a",
                "insert into records (a) values (1) limit 1",
                "insert into \"other-cluster\".records (a) values (1)",
                "insert into \"../other\" (a) values (1)",
                "insert into records (a) values (1); delete from records where _id = 'a'"
        };
        for (String sql : examples) assertThrows(SQLException.class, () -> parse(sql), sql);
    }

    private static SqlInsert parse(String sql) throws SQLException {
        return (SqlInsert) SqlCommands.parse(sql, null);
    }
}
