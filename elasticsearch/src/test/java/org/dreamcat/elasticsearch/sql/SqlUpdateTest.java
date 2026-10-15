package org.dreamcat.elasticsearch.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dreamcat.common.json.JsonUtil;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Collections;

class SqlUpdateTest {

    @Test
    void parsesAliasedUpdateWithMultipleAssignments() throws Exception {
        SqlUpdate update = SqlUpdate.parse(
                "UPDATE records t SET t.status = 'done', t.score = 100 WHERE t._id = 'doc-1'", null);
        assertEquals("records", update.index);
        assertEquals("doc-1", update.id);
        assertEquals("done", update.doc.get("status"));
        assertEquals(100L, update.doc.get("score"));
        assertEquals("{\"doc\":{\"status\":\"done\",\"score\":100}}", JsonUtil.toJson(SqlSupport.map("doc", update.doc)));
    }

    @Test
    void acceptsUnqualifiedColumnsNumericIdsAndParentheses() throws Exception {
        SqlUpdate update = SqlUpdate.parse(
                "update records set status = 'x' where (_id = 42)", null);
        assertEquals("42", update.id);
        assertEquals("x", update.doc.get("status"));
        SqlUpdate qualified = SqlUpdate.parse(
                "update \"es-rd-test\".records t set t.flag = true where t._id = 'a b'", "es-rd-test");
        assertEquals("records", qualified.index);
        assertEquals("a b", qualified.id);
        assertEquals(Boolean.TRUE, qualified.doc.get("flag"));
    }

    @Test
    void acceptsNullAssignment() throws Exception {
        SqlUpdate update = SqlUpdate.parse("update records set status = null where _id = 'a'", null);
        assertTrue(update.doc.containsKey("status"));
        assertNull(update.doc.get("status"));
        assertTrue(JsonUtil.toJson(SqlSupport.map("doc", update.doc)).contains("null"));
    }

    @Test
    void acceptsQuotedIdentifiers() throws Exception {
        SqlUpdate update = SqlUpdate.parse(
                "update \"records\" set \"status\" = 'x' where \"_id\" = 'doc-1'", null);
        assertEquals("records", update.index);
        assertEquals("doc-1", update.id);
        assertEquals("x", update.doc.get("status"));
        SqlUpdate qualified = SqlUpdate.parse(
                "UPDATE \"records\" SET \"records\".\"status\" = 'y' WHERE \"records\".\"_id\" = 'doc-2'", null);
        assertEquals("records", qualified.index);
        assertEquals("doc-2", qualified.id);
        assertEquals("y", qualified.doc.get("status"));
        SqlQuery query = SqlQuery.parse("select \"score\" from \"records\" where \"_id\" = 'a'");
        assertEquals("records", query.index);
        assertEquals(Collections.singletonList("score"), query.columns);
    }

    @Test
    void rejectsUpdatesWithoutPrimaryKeyCondition() {
        String[] examples = {
                "update records set status = 'x'",
                "update records set status = 'x' where score = 1",
                "update records set status = 'x' where _id != 'a'",
                "update records set status = 'x' where _id > 'a'",
                "update records set status = 'x' where _id in ('a', 'b')",
                "update records set status = 'x' where _id between 'a' and 'b'",
                "update records set status = 'x' where _id is null",
                "update records set status = 'x' where _id = 'a' and _id = 'b'",
                "update records set status = 'x' where _id = 'a' or _id = 'b'",
                "update records set status = 'x' where _id = 'a' and score > 1",
                "update records set status = 'x' where 'a' = _id",
                "update records set status = 'x' where _id = status"
        };
        for (String sql : examples) assertThrows(SQLException.class, () -> SqlUpdate.parse(sql, null), sql);
    }

    @Test
    void ignoresNonPrimaryKeyEqualityConditions() throws Exception {
        SqlUpdate update = SqlUpdate.parse(
                "update records t set t.\"name\" = 'Timothy' where t.user_id = 26 and t.name = 'old' "
                        + "and t.\"location.city\" = '石家庄市' and t.is_active = false and t._id = 'doc-1'",
                null);
        assertEquals("doc-1", update.id);
        assertEquals("Timothy", update.doc.get("name"));
    }

    @Test
    void rejectsUnsupportedAssignmentsAndClauses() {
        String[] examples = {
                "update records set _id = 'b' where _id = 'a'",
                "update records set status = score where _id = 'a'",
                "update records set status = 1 + 1 where _id = 'a'",
                "update records set status = 'a', status = 'b' where _id = 'a'",
                "update records set (a, b) = (1, 2) where _id = 'a'",
                "update records set status = 'x' where _id = 'a' order by status",
                "update records set status = 'x' where _id = 'a' limit 1",
                "update records a, records2 b set a.status = 'x' where a._id = 'a'",
                "update records set status = 'x' where _id = 'a'; delete from records",
                "delete from records"
        };
        for (String sql : examples) assertThrows(SQLException.class, () -> SqlUpdate.parse(sql, null), sql);
    }

    @Test
    void rejectsUnknownCatalog() {
        assertThrows(SQLException.class, () -> SqlUpdate.parse(
                "update \"other-cluster\".records t set status = 'x' where t._id = 'a'", "es-rd-test"));
        assertThrows(SQLException.class, () -> SqlUpdate.parse(
                "update \"es-rd-test\".records t set status = 'x' where t._id = 'a'", null));
    }

    @Test
    void dispatchesSelectAndUpdateAndRejectsOtherStatements() throws Exception {
        assertTrue(SqlCommands.parse("select score from records", null) instanceof SqlQuery);
        assertTrue(SqlCommands.parse(
                "update records set status = 'x' where _id = 'a'", null) instanceof SqlUpdate);
        assertThrows(SQLException.class, () -> SqlCommands.parse("delete from records", null));
        assertThrows(SQLException.class, () -> SqlCommands.parse("insert into records (a) values (1)", null));
        assertThrows(SQLException.class, () -> SqlQuery.parse(
                "update records set status = 'x' where _id = 'a'"));
    }
}
