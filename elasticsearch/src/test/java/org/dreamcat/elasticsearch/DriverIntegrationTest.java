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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        url = "jdbc:es7://127.0.0.1:" + server.getAddress().getPort();
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
        for (String prefix : new String[]{"jdbc:es:", "jdbc:elasticsearch:", "jdbc:es7:"}) {
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
            assertEquals(2, rows.getMetaData().getColumnCount());
            assertTrue(rows.next());
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
}
