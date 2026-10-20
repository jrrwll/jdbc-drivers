package org.dreamcat.elasticsearch.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

class SqlDeleteTest {

    @Test
    void acceptsIdEqualityListsAndDisjunctions() throws Exception {
        assertEquals(Collections.singletonList("42"), parse("delete from records where _id = 42").ids);
        assertEquals(Collections.singletonList("a"), parse("delete from records where _id in ('a')").ids);
        assertEquals(Collections.singletonList("a"), parse("delete from records where 'a' = _id").ids);
        assertEquals(Arrays.asList("a", "b"), parse("delete from records where _id in ('a', 'b', 'a')").ids);
        assertEquals(Arrays.asList("a", "b", "c"), parse("delete from records where "
                + "(_id = 'a' or _id = 'b') or _id in ('b', 'c')").ids);
        assertTrue(parse("delete from records where _id = 'a' and _id = 'b'").ids.isEmpty());
        assertEquals(Collections.singletonList("b"), parse("delete from records where "
                + "_id in ('a', 'b') and _id in ('b', 'c')").ids);
    }

    @Test
    void recordsIgnoredConjunctiveEqualitiesAndResolvesAliases() throws Exception {
        SqlDelete delete = (SqlDelete) SqlCommands.parse("delete from \"test-cluster\".\"records\" t "
                + "where (t.\"_id\" in ('a', 'b')) and (t.score = 1 and t.active = false)", "test-cluster");
        assertEquals("records", delete.index);
        assertEquals(Arrays.asList("a", "b"), delete.ids);
        assertEquals(Arrays.asList("score", "active"), delete.ignoredFields);
        assertTrue(parse("delete from records where records._id = 'a'").ignoredFields.isEmpty());
    }

    @Test
    void rejectsEveryUnsupportedConditionEvenWhenAnIdIsPresent() {
        String[] conditions = {
                "_id != 'a'", "_id > 'a'", "_id not in ('a', 'b')", "_id is null",
                "_id between 'a' and 'z'", "_id like 'a%'", "not (_id = 'a')",
                "score > 1", "score in (1, 2)", "name like 'a%'", "score is null",
                "score = other", "score = null", "score = abs(1)", "score = 1 + 1",
                "(_id = 'a' or score = 1)", "(_id = 'a' or (_id = 'b' and score = 1))",
                "_id in (select _id from other)", "_id in ('b', null)", "_id in ('b', other)",
                "_id = null", "_id = ''", "_id = other", "_id = ?", "1 = 1"
        };
        for (String condition : conditions) {
            String sql = "delete from records where _id = 'a' and (" + condition + ")";
            assertThrows(SQLException.class, () -> parse(sql), sql);
        }
    }

    @Test
    void rejectsMissingIdsAndUnsupportedStatements() {
        String[] examples = {
                "delete from records", "delete from records where score = 1",
                "delete from records where score = 1 and name = 'Ada'",
                "delete from records where _id = 'a' or score = 1",
                "delete from records where _id = 'a' order by score",
                "delete from records where _id = 'a' limit 1",
                "delete from records where _id = 'a' returning _id",
                "delete t from records t where t._id = 'a'",
                "delete from records using other where records._id = 'a'",
                "delete from records t join other o on t._id = o._id where t._id = 'a'",
                "delete from \"other-cluster\".records where _id = 'a'",
                "delete from \"../records\" where _id = 'a'",
                "delete from records where _id = 'a'; delete from records"
        };
        for (String sql : examples) assertThrows(SQLException.class, () -> parse(sql), sql);
    }

    private static SqlDelete parse(String sql) throws SQLException {
        return (SqlDelete) SqlCommands.parse(sql, null);
    }
}
