package org.dreamcat.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

class SqlQueryTest {

    @Test
    void acceptsOnlyLiteralProbesWithoutFrom() throws Exception {
        assertTrue(SqlQuery.parse("select 1").isConnectionProbe());
        SqlQuery probe = SqlQuery.parse("select 2 as ping, 'ok' as status limit 1");
        assertTrue(probe.isConnectionProbe());
        assertEquals(Arrays.asList("ping", "status"), probe.columns);
        assertEquals(2L, probe.probeRow.get("ping"));
        assertEquals("ok", probe.probeRow.get("status"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select c"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select 1 where 1 = 1"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select 1 limit 0"));
    }

    @Test
    void translatesSampleQuery() throws Exception {
        SqlQuery query = SqlQuery.parse("select c1, c2, c3 from index_name where "
                + "(c1 = 1 and c5 not like 'prefix%') or "
                + "(c2 in ('x', 'y') and c3 between '2026-01-01' and '2026-02-01' and c4 >= 100) "
                + "order by ctime desc, id asc limit 50 offset 20");
        assertEquals("index_name", query.index);
        assertEquals(Arrays.asList("c1", "c2", "c3"), query.columns);
        assertEquals(50, query.body.get("size"));
        assertEquals(20, query.body.get("from"));
        assertEquals(Arrays.asList(Collections.singletonMap("ctime", "desc"), Collections.singletonMap("id", "asc")),
                query.body.get("sort"));

        String dsl = org.dreamcat.common.json.JsonUtil.toJson(query.body.get("query"));
        assertTrue(dsl.contains("\"minimum_should_match\":1"), dsl);
        assertTrue(dsl.contains("\"wildcard\":{\"c5\":\"prefix*\"}"), dsl);
        assertTrue(dsl.contains("\"terms\":{\"c2\":[\"x\",\"y\"]}"), dsl);
        assertTrue(dsl.contains("\"gte\":\"2026-01-01\""), dsl);
        assertTrue(dsl.contains("\"lte\":\"2026-02-01\""), dsl);
    }

    @Test
    void appliesDefaultLimitAndDottedPaths() throws Exception {
        SqlQuery query = SqlQuery.parse("select user.name from records where user.name like 'A_%'");
        assertEquals(500, query.body.get("size"));
        assertEquals(Collections.singletonList("user.name"), query.columns);
        assertTrue(org.dreamcat.common.json.JsonUtil.toJson(query.body).contains("\"user.name\":\"A?*\""));
        assertEquals(0, SqlQuery.parse("select * from records limit 0").body.get("size"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select * from records limit 2.5"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select * from records limit -1"));
        assertTrue(org.dreamcat.common.json.JsonUtil.toJson(
                        SqlQuery.parse("select c from records where active = true").body)
                .contains("\"active\":true"));
        assertEquals(-5L, ((Map<?, ?>) ((Map<?, ?>) SqlQuery.parse("select c from records where c = -5")
                .body.get("query")).get("term")).get("c"));
        assertTrue(org.dreamcat.common.json.JsonUtil.toJson(
                        SqlQuery.parse("select c from records where c like 'a*?%'").body)
                .contains("\"a\\\\*\\\\?*\""));
    }

    @Test
    void supportsIdeaCatalogQualifiedAliasedSelect() throws Exception {
        SqlQuery query = SqlQuery.parse("SELECT t.* FROM \"es-rd-test\".talent_practice_info t", "es-rd-test");
        assertEquals("talent_practice_info", query.index);
        assertTrue(query.allColumns);
        assertEquals(Boolean.TRUE, query.body.get("_source"));
        SqlQuery columns = SqlQuery.parse("select t._id, t.user.name from \"es-rd-test\".talent_practice_info t "
                + "where t.user.name like 'A%' order by t.user.name desc", "es-rd-test");
        assertEquals(Arrays.asList("_id", "user.name"), columns.columns);
        assertEquals(Collections.singletonList("user.name"), columns.body.get("_source"));
        assertTrue(org.dreamcat.common.json.JsonUtil.toJson(columns.body).contains("\"user.name\":\"A*\""));
        assertEquals(Collections.singletonList(Collections.singletonMap("user.name", "desc")),
                columns.body.get("sort"));
        assertThrows(SQLException.class,
                () -> SqlQuery.parse("select t.* from \"other-cluster\".talent_practice_info t", "es-rd-test"));
        assertThrows(SQLException.class, () -> SqlQuery.parse("select x.* from talent_practice_info t", "es-rd-test"));
    }

    @Test
    void rejectsUnsupportedSql() {
        String[] examples = {
                "select count(*) from records",
                "select a from records join other on records.id = other.id",
                "select distinct a from records",
                "select a from records group by a",
                "select a from records union select a from other",
                "select a from records where a in (select a from other)",
                "select a + 1 from records",
                "with q as (select a from records) select a from q",
                "select a from records for update",
                "select a from records; delete from records",
                "delete from records"
        };
        for (String sql : examples) assertThrows(SQLException.class, () -> SqlQuery.parse(sql), sql);
    }
}
