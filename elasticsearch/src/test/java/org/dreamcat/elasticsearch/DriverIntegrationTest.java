package org.dreamcat.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.dreamcat.common.codec.Base64Util;
import org.dreamcat.common.json.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class DriverIntegrationTest {

    private HttpServer server;
    private String url;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> body = new AtomicReference<>();
    private final AtomicBoolean rejectRoot = new AtomicBoolean();
    private final AtomicInteger clusterMappingRequests = new AtomicInteger();
    private final AtomicReference<Map<String, Object>> updateBody = new AtomicReference<>();
    private final AtomicReference<String> updatePath = new AtomicReference<>();
    private final AtomicBoolean updateNotFound = new AtomicBoolean();
    private final AtomicReference<String> bulkBody = new AtomicReference<>();
    private final AtomicReference<String> bulkResponse = new AtomicReference<>();
    private final AtomicInteger bulkRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/records/_search", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) request.write(buffer, 0, count);
            body.set(JsonUtil.fromJsonObject(new String(request.toByteArray(), StandardCharsets.UTF_8)));
            byte[] response = (
                    "{\"hits\":{\"hits\":[{\"_id\":\"doc-1\",\"_source\":{\"user\":{\"name\":\"Ada\"},\"score\":42}},"
                            + "{\"_id\":\"doc-2\",\"_source\":{\"user\":{\"name\":\"Lin\"}}}]}}").getBytes(
                    StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (java.io.OutputStream stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        server.createContext("/_cat/indices", exchange -> reply(exchange,
                "[{\"index\":\"records\"},{\"index\":\"other\"}]"));
        server.createContext("/records/_mapping", exchange -> reply(exchange,
                "{\"records\":{\"mappings\":{\"properties\":{\"user\":{\"properties\":{\"name\":{\"type\":\"keyword"
                        + "\"}}},"
                        + "\"score\":{\"type\":\"integer\"},\"hidden\":{\"type\":\"keyword\",\"index\":false}}}}}"));
        server.createContext("/_mapping", exchange -> {
            clusterMappingRequests.incrementAndGet();
            reply(exchange,
                    "{\"records\":{\"mappings\":{\"properties\":{\"user\":{\"properties\":{\"name\":{\"type"
                            + "\":\"keyword\"}}},"
                            + "\"score\":{\"type\":\"integer\"},\"hidden\":{\"type\":\"keyword\",\"index\":false}}}}}");
        });
        server.createContext("/records/_update", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            updatePath.set(exchange.getRequestURI().getRawPath());
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) request.write(buffer, 0, count);
            updateBody.set(JsonUtil.fromJsonObject(new String(request.toByteArray(), StandardCharsets.UTF_8)));
            reply(exchange, updateNotFound.get() ? "{\"result\":\"not_found\"}" : "{\"result\":\"updated\"}");
        });
        server.createContext("/records/_bulk", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) request.write(buffer, 0, count);
            String payload = new String(request.toByteArray(), StandardCharsets.UTF_8);
            bulkBody.set(payload);
            bulkRequests.incrementAndGet();
            if (bulkResponse.get() != null) {
                reply(exchange, bulkResponse.get());
                return;
            }
            assertTrue(payload.endsWith("\n"));
            String[] lines = payload.split("\n");
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                Map<String, Object> action = JsonUtil.fromJsonObject(lines[i]);
                String operation = action.keySet().iterator().next();
                Map<?, ?> metadata = (Map<?, ?>) action.get(operation);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("_id", metadata.get("_id"));
                if ("create".equals(operation)) {
                    i++; // Document source follows each create action.
                    item.put("status", 201);
                    item.put("result", "created");
                } else {
                    assertEquals("delete", operation);
                    boolean missing = "missing".equals(metadata.get("_id"));
                    item.put("status", missing ? 404 : 200);
                    item.put("result", missing ? "not_found" : "deleted");
                }
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put(operation, item);
                items.add(wrapped);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", items);
            reply(exchange, JsonUtil.toJson(response));
        });
        server.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (rejectRoot.get() || authorization != null
                    && !authorization.equals("Basic " + Base64Util.encodeAsString("alice:new-secret"))) {
                reply(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else {
                reply(exchange, "{\"cluster_name\":\"test-cluster\",\"version\":{\"number\":\"7.10.0\"}}");
            }
        });
        server.start();
        url = "jdbc:elasticsearch://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        reply(exchange, 200, json);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws java.io.IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (java.io.OutputStream stream = exchange.getResponseBody()) {
            stream.write(response);
        }
    }

    @Test
    void connectsUsingBothElasticsearchUrlPrefixesAndLegacyPrefix() throws Exception {
        Driver driver = new Driver();
        for (String prefix : new String[]{"jdbc:es:", "jdbc:elasticsearch:"}) {
            String endpoint = prefix + "//127.0.0.1:" + server.getAddress().getPort();
            assertTrue(driver.acceptsURL(endpoint));
            try (Connection connection = DriverManager.getConnection(endpoint + "?index=rec%&index=absent");
                    Statement statement = connection.createStatement()) {
                assertEquals(endpoint, connection.getMetaData().getURL());
                assertEquals("Elasticsearch JDBC Driver", connection.getMetaData().getDriverName());
                try (ResultSet result = statement.executeQuery("select score from records limit 1")) {
                    assertTrue(result.next());
                    assertEquals(42, result.getInt("score"));
                }
                assertThrows(SQLException.class, () -> statement.executeQuery("select * from other"));
            }
        }
        assertFalse(driver.acceptsURL("jdbc:es8://localhost:9200"));
        assertFalse(driver.acceptsURL(null));
        assertNull(driver.connect("jdbc:mysql://localhost", new Properties()));
    }

    @Test
    void limitsRowsRequestedAndReturnedWithoutOverridingSqlLimit() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertEquals(0, statement.getMaxRows());
            statement.setMaxRows(1);
            assertEquals(1L, statement.getLargeMaxRows());
            try (ResultSet rows = statement.executeQuery("select score from records limit 50 offset 20")) {
                assertEquals(1, body.get().get("size"));
                assertEquals(20, body.get().get("from"));
                assertTrue(rows.next());
                assertFalse(rows.next());
            }
            statement.setMaxRows(100);
            statement.execute("select score from records limit 5");
            assertEquals(5, body.get().get("size"));
            ResultSet previous = statement.getResultSet();
            assertFalse(statement.getMoreResults());
            assertTrue(previous.isClosed());
            assertNull(statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
            assertEquals(-1L, statement.getLargeUpdateCount());
            statement.setMaxRows(0);
            statement.executeQuery("select score from records").close();
            assertEquals(500, body.get().get("size"));
            statement.setLargeMaxRows(Long.MAX_VALUE);
            statement.executeQuery("select score from records limit 5").close();
            assertEquals(5, body.get().get("size"));
            assertThrows(SQLException.class, () -> statement.setMaxRows(-1));
            assertThrows(SQLException.class, () -> statement.setLargeMaxRows(-1));
        }
    }

    @Test
    void listsTablesUsingCatalogAndSchemaPatterns() throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            DatabaseMetaData meta = connection.getMetaData();
            assertEquals("test-cluster", connection.getCatalog());
            connection.setCatalog(connection.getCatalog());
            assertThrows(SQLException.class, () -> connection.setCatalog("other-cluster"));
            assertEquals("\\", meta.getSearchStringEscape());
            assertFalse(meta.supportsSchemasInDataManipulation());
            for (String schema : new String[]{null, "", "%", "test-cluster", "test-%"}) {
                try (ResultSet tables = meta.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
                    assertTrue(tables.next());
                    assertEquals("records", tables.getString("TABLE_NAME"));
                    assertNull(tables.getString("TABLE_SCHEM"));
                    assertTrue(tables.next());
                    assertEquals("other", tables.getString("TABLE_NAME"));
                    assertFalse(tables.next());
                }
            }
            try (ResultSet tables = meta.getTables(connection.getCatalog(), "missing", "%", null)) {
                assertFalse(tables.next());
            }
            try (ResultSet columns = meta.getColumns(connection.getCatalog(), "%", "records", "%")) {
                assertTrue(columns.next());
            }
        }
    }

    @Test
    void readsMetadataWhenIdeaUsesCatalogAsSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            try (ResultSet columns = meta.getColumns(catalog, catalog, "records", "%")) {
                assertTrue(columns.next());
                assertEquals("_id", columns.getString("COLUMN_NAME"));
                assertEquals(catalog, columns.getString("TABLE_CAT"));
                assertNull(columns.getString("TABLE_SCHEM"));
                assertTrue(columns.next());
                assertEquals("user.name", columns.getString("COLUMN_NAME"));
            }
            try (ResultSet keys = meta.getPrimaryKeys(catalog, catalog, "records")) {
                assertTrue(keys.next());
                assertEquals("_id", keys.getString("COLUMN_NAME"));
                assertFalse(keys.next());
            }
            try (ResultSet indexes = meta.getIndexInfo(catalog, catalog, "records", false, false)) {
                assertTrue(indexes.next());
                assertEquals("user.name", indexes.getString("COLUMN_NAME"));
            }
            try (ResultSet schemas = meta.getSchemas(catalog, "%")) {
                assertFalse(schemas.next());
            }
            try (ResultSet tables = meta.getTables("wrong-cluster", catalog, "%", null)) {
                assertFalse(tables.next());
            }
            try (ResultSet columns = meta.getColumns(catalog, "missing", "records", "%")) {
                assertFalse(columns.next());
            }
            for (String schema : new String[]{"missing", "%", "test-%"}) {
                // Unlike getTables/getColumns, these APIs take an exact schema, not a pattern.
                try (ResultSet keys = meta.getPrimaryKeys(catalog, schema, "records")) {
                    assertFalse(keys.next());
                }
                try (ResultSet indexes = meta.getIndexInfo(catalog, schema, "records", false, false)) {
                    assertFalse(indexes.next());
                }
            }
        }
    }

    @Test
    void readsEscapedExactTableMappingWithoutReadingOtherIndexes() throws Exception {
        AtomicInteger exactRequests = new AtomicInteger();
        server.createContext("/some_index/_mapping", exchange -> {
            exactRequests.incrementAndGet();
            reply(exchange, "{\"some_index\":{\"mappings\":{\"properties\":{\"score\":{\"type\":\"integer\"}}}}}");
        });
        for (String suffix : new String[]{"", "?index=some_index"}) {
            try (Connection connection = DriverManager.getConnection(url + suffix);
                    ResultSet columns = connection.getMetaData().getColumns(null, null, "some\\_index", "%")) {
                assertTrue(columns.next());
                assertEquals("some_index", columns.getString("TABLE_NAME"));
                assertTrue(columns.next());
                assertEquals("score", columns.getString("COLUMN_NAME"));
                assertFalse(columns.next());
            }
        }
        assertEquals(2, exactRequests.get());
        assertEquals(0, clusterMappingRequests.get());
    }

    @Test
    void restrictsMetadataAndSearchToUrlIndexPatterns() throws Exception {
        try (Connection connection = DriverManager.getConnection(url + "?index=rec%&index=other_index")) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet tables = meta.getTables(null, null, "%", null)) {
                assertTrue(tables.next());
                assertEquals("records", tables.getString("TABLE_NAME"));
                assertFalse(tables.next());
            }
            try (ResultSet columns = meta.getColumns(null, null, "other", "%")) {
                assertFalse(columns.next());
            }
            try (ResultSet columns = meta.getColumns(null, null, "%", "%")) {
                assertTrue(columns.next());
                assertEquals("records", columns.getString("TABLE_NAME"));
                while (columns.next()) assertEquals("records", columns.getString("TABLE_NAME"));
            }
            assertEquals(0, clusterMappingRequests.get(), "Filtered metadata must not request cluster-wide mapping");
            try (ResultSet keys = meta.getPrimaryKeys(null, null, "other")) {
                assertFalse(keys.next());
            }
            try (ResultSet indexes = meta.getIndexInfo(null, null, "other", false, false)) {
                assertFalse(indexes.next());
            }
            try (Statement statement = connection.createStatement()) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.executeQuery("select * from other"));
                assertTrue(denied.getMessage().contains("Index is not allowed"), denied.getMessage());
                try (ResultSet rows = statement.executeQuery("select * from records limit 1")) {
                    assertTrue(rows.next());
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(url + "?index=rec%&index=other");
                ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
            assertTrue(tables.next());
            assertEquals("records", tables.getString("TABLE_NAME"));
            assertTrue(tables.next());
            assertEquals("other", tables.getString("TABLE_NAME"));
            assertFalse(tables.next());
        }
    }

    @Test
    void searchesWithPropertyCredentialsAndReadsRows() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "alice");
        properties.setProperty("password", "new-secret");
        try (Connection connection = DriverManager.getConnection(url + "?user=old&password=old", properties);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select user.name, score, missing from records order by score desc")) {
            assertEquals("Basic " + Base64Util.encodeAsString("alice:new-secret"), auth.get());
            assertEquals(500, body.get().get("size"));
            assertEquals(3, rows.getMetaData().getColumnCount());
            assertEquals("user.name", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals("Ada", rows.getString("user.name"));
            assertEquals(42, rows.getInt("score"));
            assertNull(rows.getObject("missing"));
            assertTrue(rows.wasNull());
            assertTrue(rows.next());
            assertEquals("Lin", rows.getString(1));
            assertEquals(0, rows.getInt("score"));
            assertTrue(rows.wasNull());
            assertFalse(rows.next());
        }
    }

    @Test
    void rejectsWrongPasswordDuringConnect() {
        Properties properties = new Properties();
        properties.setProperty("user", "alice");
        properties.setProperty("password", "wrong");
        SQLException error = assertThrows(SQLException.class, () -> DriverManager.getConnection(url, properties));
        assertTrue(error.getMessage().contains("401"), error.getMessage() + "; cause=" + error.getCause());
    }

    @Test
    void checksConnectionValidityAgainstElasticsearch() throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            assertTrue(connection.isValid(1));
            rejectRoot.set(true);
            assertFalse(connection.isValid(1));
            assertThrows(SQLException.class, () -> connection.isValid(-1));
        }
    }

    @Test
    void supportsSelectStarAndCloseState() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertTrue(statement.execute("select * from records limit 1"));
            ResultSet rows = statement.getResultSet();
            assertEquals(3, rows.getMetaData().getColumnCount());
            assertEquals("_id", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals("doc-1", rows.getString("_id"));
            assertEquals("Ada", rows.getString("user.name"));
            statement.close();
            assertTrue(statement.isClosed());
            assertTrue(rows.isClosed());
            assertThrows(SQLException.class, rows::next);
        }
    }

    @Test
    void executesIdeaCatalogQualifiedAliasedSelect() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT t.* FROM \"test-cluster\".records t")) {
            assertTrue(rows.next());
            assertEquals("Ada", rows.getString("user.name"));
            assertEquals(500, body.get().get("size"));
            assertFalse(body.get().containsKey("schema"));
        }
    }

    @Test
    void connectionProbeDoesNotCallElasticsearch() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 1 AS ping LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("ping"));
            assertFalse(rows.next());
            assertNull(body.get());
        }
    }

    @Test
    void exposesJdbcMetadata() throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            DatabaseMetaData meta = connection.getMetaData();
            assertNull(connection.getWarnings());
            connection.clearWarnings();
            assertEquals("7.10.0", meta.getDatabaseProductVersion());
            assertEquals(7, meta.getDatabaseMajorVersion());
            assertEquals(10, meta.getDatabaseMinorVersion());
            try (ResultSet catalogs = meta.getCatalogs()) {
                assertTrue(catalogs.next());
                assertEquals("test-cluster", catalogs.getString("TABLE_CAT"));
                assertFalse(catalogs.next());
            }
            try (ResultSet schemas = meta.getSchemas()) {
                assertFalse(schemas.next());
            }
            try (ResultSet tables = meta.getTables(null, null, "rec%", new String[]{"TABLE"})) {
                assertTrue(tables.next());
                assertEquals("records", tables.getString("TABLE_NAME"));
                assertEquals("test-cluster", tables.getString("TABLE_CAT"));
                assertFalse(tables.next());
            }
            try (ResultSet columns = meta.getColumns(null, null, "records", "%")) {
                assertTrue(columns.next());
                assertEquals("_id", columns.getString("COLUMN_NAME"));
                assertTrue(columns.next());
                assertEquals("user.name", columns.getString("COLUMN_NAME"));
                assertEquals("test-cluster", columns.getString("TABLE_CAT"));
                assertEquals(2, columns.getInt("ORDINAL_POSITION"));
                assertTrue(columns.next());
                assertEquals("score", columns.getString("COLUMN_NAME"));
                assertEquals(java.sql.Types.INTEGER, columns.getInt("DATA_TYPE"));
                assertEquals(3, columns.getInt("ORDINAL_POSITION"));
            }
            try (ResultSet columns = meta.getColumns(null, null, "records", "score")) {
                assertTrue(columns.next());
                assertEquals(3, columns.getInt("ORDINAL_POSITION"));
                assertFalse(columns.next());
            }
            try (ResultSet keys = meta.getPrimaryKeys(null, null, "records")) {
                assertTrue(keys.next());
                assertEquals("_id", keys.getString("COLUMN_NAME"));
                assertEquals("test-cluster", keys.getString("TABLE_CAT"));
                assertFalse(keys.next());
            }
            try (ResultSet indexes = meta.getIndexInfo(null, null, "records", false, false)) {
                assertTrue(indexes.next());
                assertEquals("user.name", indexes.getString("COLUMN_NAME"));
                assertTrue(indexes.next());
                assertEquals("score", indexes.getString("COLUMN_NAME"));
                assertFalse(indexes.next());
            }
        }
    }

    @Test
    void readsExplicitIdPseudoColumn() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select _id, user.name from records")) {
            assertNull(statement.getWarnings());
            statement.clearWarnings();
            assertEquals(java.util.Collections.singletonList("user.name"), body.get().get("_source"));
            assertTrue(rows.next());
            assertEquals("doc-1", rows.getString("_id"));
        }
    }

    @Test
    void insertsRowsWithCreateSemanticsAndOptionalIds() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate("insert into records (_id, name, score) "
                    + "values ('a/b', 'Ada', 7), ('中文', 'Lin', null)"));
            assertEquals(2, statement.getUpdateCount());
            assertNull(statement.getResultSet());
            assertEquals("{\"create\":{\"_id\":\"a/b\"}}\n{\"name\":\"Ada\",\"score\":7}\n"
                    + "{\"create\":{\"_id\":\"中文\"}}\n{\"name\":\"Lin\",\"score\":null}\n", bulkBody.get());
            assertFalse(statement.execute("insert into records (name) values ('Auto')"));
            assertEquals("{\"create\":{}}\n{\"name\":\"Auto\"}\n", bulkBody.get());
            assertEquals(1L, statement.getLargeUpdateCount());
            assertThrows(SQLException.class,
                    () -> statement.executeQuery("insert into records (score) values (7)"));
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    void deletesOnlyIdsAndExposesIgnoredFieldWarnings() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            ResultSet previous = statement.executeQuery("select score from records");
            assertEquals(2, statement.executeUpdate("delete from records t where "
                    + "t._id in ('a/b', '中文', 'a/b', 'missing') and t.score = 1 and t.name = 'old'"));
            assertTrue(previous.isClosed());
            assertNull(statement.getResultSet());
            assertEquals(2, statement.getUpdateCount());
            assertEquals("{\"delete\":{\"_id\":\"a/b\"}}\n{\"delete\":{\"_id\":\"中文\"}}\n"
                    + "{\"delete\":{\"_id\":\"missing\"}}\n", bulkBody.get());
            assertTrue(statement.getWarnings().getMessage().contains("score"));
            assertTrue(statement.getWarnings().getMessage().contains("name"));
            assertEquals("01000", statement.getWarnings().getSQLState());
            statement.clearWarnings();
            assertNull(statement.getWarnings());
            assertFalse(statement.execute("delete from records where _id = 'a' or _id = 'b'"));
            assertEquals(2, statement.getUpdateCount());
            assertNull(statement.getWarnings());
            assertEquals(0L, statement.executeLargeUpdate("delete from records where _id = 'missing'"));
            int requests = bulkRequests.get();
            assertEquals(0, statement.executeUpdate("delete from records where _id = 'a' and _id = 'b'"));
            assertEquals(requests, bulkRequests.get());
        }
    }

    @Test
    void rejectsUnsafeDeletesAndDisallowedWritesBeforeHttpRequest() throws Exception {
        try (Connection connection = DriverManager.getConnection(url + "?index=records");
                Statement statement = connection.createStatement()) {
            for (String sql : new String[]{
                    "delete from records", "delete from records where score = 1",
                    "delete from records where _id = 'a' and score > 1",
                    "delete from records where _id = 'a' and score = other",
                    "delete from records where _id = 'a' or score = 1",
                    "delete from records where _id = 'a' and _id in ('a', null)",
                    "delete from records where _id = 'a' limit 1",
                    "delete from other where _id = 'a'",
                    "insert into other (_id, score) values ('a', 1)",
                    "insert into records (score) values (1), (other)"
            }) {
                assertThrows(SQLException.class, () -> statement.executeUpdate(sql), sql);
                assertEquals(-1, statement.getUpdateCount());
            }
            assertThrows(SQLException.class, () -> statement.executeQuery("delete from records where _id = 'a'"));
            assertEquals(0, bulkRequests.get());
        }
    }

    @Test
    void insertsAndDeletesThroughPreparedStatementsAndBatches() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "alice");
        properties.setProperty("password", "new-secret");
        try (Connection connection = DriverManager.getConnection(url, properties);
                PreparedStatement insert = connection.prepareStatement("insert into records (_id, name) values (?, ?)");
                PreparedStatement delete = connection.prepareStatement(
                        "delete from records where _id in (?, ?) and name = ?")) {
            insert.setString(1, "a");
            insert.setString(2, "O'Brien");
            insert.addBatch();
            insert.setString(1, "b");
            insert.setNull(2, java.sql.Types.VARCHAR);
            insert.addBatch();
            assertTrue(java.util.Arrays.equals(new int[]{1, 1}, insert.executeBatch()));
            assertEquals("Basic " + Base64Util.encodeAsString("alice:new-secret"), auth.get());
            delete.setString(1, "a");
            delete.setString(2, "missing");
            delete.setString(3, "ignored");
            assertFalse(delete.execute());
            assertEquals(1, delete.getUpdateCount());
            assertTrue(delete.getWarnings().getMessage().contains("name"));
            delete.clearWarnings();
            assertNull(delete.getWarnings());
            delete.addBatch();
            assertTrue(java.util.Arrays.equals(new long[]{1}, delete.executeLargeBatch()));
            assertTrue(delete.getWarnings().getMessage().contains("name"));
            delete.setString(1, "b");
            assertEquals(1L, delete.executeLargeUpdate());
            assertThrows(SQLException.class, delete::executeQuery);
            assertNull(delete.getWarnings());
        }
    }

    @Test
    void reportsBulkItemErrorsAndRejectsMalformedResponses() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            bulkResponse.set("{\"errors\":true,\"items\":[{\"create\":{\"status\":201,\"result\":\"created\"}},"
                    + "{\"create\":{\"_id\":\"b\",\"status\":409,"
                    + "\"error\":{\"type\":\"version_conflict_engine_exception\"}}}]}");
            SQLException conflict = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into records (_id) values ('a'), ('b')"));
            assertTrue(conflict.getMessage().contains("409"));
            assertTrue(conflict.getMessage().contains("1 document(s) succeeded"));
            assertEquals(-1, statement.getUpdateCount());
            bulkResponse.set("{\"errors\":true,\"items\":[{\"delete\":{\"_id\":\"a\",\"status\":404,"
                    + "\"error\":{\"type\":\"index_not_found_exception\"}}}]}");
            assertThrows(SQLException.class, () -> statement.executeUpdate("delete from records where _id = 'a'"));
            for (String response : new String[]{"null", "not json", "{}", "{\"items\":[]}",
                    "{\"items\":[{}]}", "{\"items\":[{\"delete\":{\"status\":200,\"result\":\"noop\"}}]}"}) {
                bulkResponse.set(response);
                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("delete from records where _id = 'a'"), response);
            }
        }
    }

    @Test
    void preparedBatchReportsCompletedStatementsBeforeInvalidDelete() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement delete = connection.prepareStatement("delete from records where _id = ?")) {
            delete.setString(1, "a");
            delete.addBatch();
            delete.setNull(1, java.sql.Types.VARCHAR);
            delete.addBatch();
            BatchUpdateException failure = assertThrows(BatchUpdateException.class, delete::executeBatch);
            assertTrue(java.util.Arrays.equals(new int[]{1}, failure.getUpdateCounts()));
            assertEquals(1, bulkRequests.get());
            assertEquals(0, delete.executeBatch().length);
        }
    }

    @Test
    void updatesDocumentByPrimaryKey() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "update records t set t.status = 'done', t.score = 7 where t._id = 'doc-1'"));
            assertEquals(1, statement.getUpdateCount());
            assertEquals(1L, statement.getLargeUpdateCount());
            assertNull(statement.getResultSet());
            assertEquals("/records/_update/doc-1", updatePath.get());
            assertEquals("{\"doc\":{\"status\":\"done\",\"score\":7}}", JsonUtil.toJson(updateBody.get()));

            updateNotFound.set(true);
            assertEquals(0, statement.executeUpdate("update records set status = 'x' where _id = 'missing'"));
            assertEquals(0, statement.getUpdateCount());
            assertEquals(0L, statement.getLargeUpdateCount());
            updateNotFound.set(false);

            assertFalse(statement.execute("update records set flag = true where _id = 'doc-2'"));
            assertEquals(1, statement.getUpdateCount());
            assertNull(statement.getResultSet());
        }
    }

    @Test
    void encodesUpdateIdPathSegment() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("update records set status = 'x' where _id = 'a b/c'"));
            assertEquals("/records/_update/a%20b%2Fc", updatePath.get());
        }
    }

    @Test
    void rejectsUpdateMisuseAndIsWritable() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertFalse(connection.isReadOnly());
            assertFalse(connection.getMetaData().isReadOnly());
            connection.setReadOnly(true);
            connection.setReadOnly(false);
            assertThrows(SQLException.class,
                    () -> statement.executeQuery("update records set status = 'x' where _id = 'a'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("select score from records"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("update records set status = 'x' where score = 1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "update records set status = 'x' where _id = 'a' and score > 1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "update records set status = 'x' where _id = 'a'", Statement.RETURN_GENERATED_KEYS));
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    void updatesThroughPreparedStatementAndTransactions() throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            assertTrue(connection.getAutoCommit());
            connection.setAutoCommit(false);
            assertFalse(connection.getAutoCommit());
            connection.commit();
            connection.rollback();
            try (PreparedStatement statement = connection.prepareStatement(
                    "update records t set t.status = ?, t.score = ? where t._id = ?")) {
                statement.setString(1, "done");
                statement.setInt(2, 9);
                statement.setString(3, "doc-1");
                assertEquals(1, statement.executeUpdate());
                assertEquals(1, statement.getUpdateCount());
                assertEquals("/records/_update/doc-1", updatePath.get());
                assertEquals("{\"doc\":{\"status\":\"done\",\"score\":9}}", JsonUtil.toJson(updateBody.get()));
            }
        }
    }

    @Test
    void runsPreparedSelectAndBatch() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement select = connection.prepareStatement("select score from records where score >= ?")) {
            select.setInt(1, 10);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(42, rows.getInt("score"));
            }
            assertTrue(JsonUtil.toJson(body.get().get("query")).contains("\"gte\":10"));
        }
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement update = connection.prepareStatement("update records set status = ? where _id = ?")) {
            update.setString(1, "a");
            update.setString(2, "doc-1");
            update.addBatch();
            update.setString(1, "b");
            update.setString(2, "doc-2");
            update.addBatch();
            int[] counts = update.executeBatch();
            assertEquals(2, counts.length);
            assertEquals(1, counts[0]);
            assertEquals(1, counts[1]);
        }
    }

    @Test
    void rejectsPreparedStatementMisuse() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement update = connection.prepareStatement(
                        "update records set status = ? where _id = ?");
                PreparedStatement select = connection.prepareStatement("select score from records")) {
            update.setString(1, "x");
            update.setString(2, "a");
            assertThrows(SQLException.class, select::executeUpdate);
            assertThrows(SQLException.class, update::executeQuery);
            assertThrows(SQLException.class, () -> connection.prepareStatement(
                    "select score from records", Statement.RETURN_GENERATED_KEYS));
        }
    }
}
