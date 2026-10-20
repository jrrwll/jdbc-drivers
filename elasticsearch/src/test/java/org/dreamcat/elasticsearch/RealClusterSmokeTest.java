package org.dreamcat.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.dreamcat.common.io.FileUtil;
import org.dreamcat.common.json.JsonUtil;
import org.dreamcat.common.sql.JdbcUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class RealClusterSmokeTest {

    @BeforeAll
    static void loadLocalProperties() throws Exception {
        FileUtil.loadDotEnvFile();
    }

    @Test
    void readsRealClusterThroughJdbc() throws Exception {
        String url = System.getProperty("ES_TEST_URL");
        String index = System.getProperty("ES_TEST_INDEX");
        assumeTrue(url != null && index != null, "Real ES test requires ES_TEST_URL and ES_TEST_INDEX");
        Properties properties = new Properties();
        String user = System.getProperty("ES_TEST_USER");
        String password = System.getProperty("ES_TEST_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);

        try (Connection connection = DriverManager.getConnection(url, properties)) {
            DatabaseMetaData meta = connection.getMetaData();
            System.out.printf("productVersion = %s, majorVersion = %s%n", meta.getDatabaseProductVersion(),
                    meta.getDatabaseMajorVersion());
            String catalog;
            try (ResultSet catalogs = meta.getCatalogs()) {
                assertTrue(catalogs.next());
                catalog = catalogs.getString("TABLE_CAT");
                assertNotNull(catalog);
            }
            try (ResultSet schemas = meta.getSchemas()) {
                assertFalse(schemas.next());
            }
            assertEquals(catalog, connection.getCatalog());
            try (ResultSet tables = meta.getTables(catalog, "%", "%", new String[]{"TABLE"})) {
                boolean found = false;
                while (tables.next()) {
                    if (index.equals(tables.getString("TABLE_NAME"))) found = true;
                }
                assertTrue(found, "Index must be included in the full table listing");
            }
            String schemaPattern = catalog.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
            String indexPattern = index.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
            try (ResultSet tables = meta.getTables(catalog, schemaPattern, indexPattern, new String[]{"TABLE"})) {
                assertTrue(tables.next());
                assertEquals(index, tables.getString("TABLE_NAME"));
            }
            try (ResultSet columns = meta.getColumns(catalog, schemaPattern, indexPattern, "%")) {
                assertTrue(columns.next());
                assertEquals("_id", columns.getString("COLUMN_NAME"));
                assertTrue(columns.next());
            }
            try (ResultSet keys = meta.getPrimaryKeys(catalog, catalog, index)) {
                assertTrue(keys.next());
                assertEquals("_id", keys.getString("COLUMN_NAME"));
            }
            try (ResultSet indexes = meta.getIndexInfo(catalog, catalog, index, false, false)) {
                assertTrue(indexes.next());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select _id from " + index + " limit 1")) {
                assertTrue(rows.next());
                assertNotNull(rows.getString("_id"));
                assertFalse(rows.next());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT t.* FROM \"" + catalog.replace("\"", "\"\"")
                            + "\"." + index + " t LIMIT 1")) {
                assertTrue(rows.next());
                assertTrue(rows.getMetaData().getColumnCount() > 0);
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void restrictsRealClusterIndexListingAndQueries() throws Exception {
        String url = System.getProperty("ES_TEST_URL");
        String index = System.getProperty("ES_TEST_INDEX");
        assumeTrue(url != null && index != null, "Real ES test requires ES_TEST_URL and ES_TEST_INDEX");
        Properties properties = new Properties();
        String user = System.getProperty("ES_TEST_USER");
        String password = System.getProperty("ES_TEST_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        String separator = url.contains("?") ? "&" : "?";
        String filteredUrl = url + separator + "index=" + URLEncoder.encode(index, StandardCharsets.UTF_8.name());
        try (Connection connection = DriverManager.getConnection(filteredUrl, properties)) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
                assertTrue(tables.next());
                assertEquals(index, tables.getString("TABLE_NAME"));
                assertFalse(tables.next());
            }
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "%", "%")) {
                assertTrue(columns.next());
                do {
                    assertEquals(index, columns.getString("TABLE_NAME"));
                } while (columns.next());
            }
            try (Statement statement = connection.createStatement()) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.executeQuery("select _id from __es_jdbc_not_allowed__"));
                assertTrue(denied.getMessage().contains("Index is not allowed"));
                try (ResultSet rows = statement.executeQuery("select _id from " + index + " limit 1")) {
                    List<Map<String, Object>> rowList = JdbcUtil.getRows(rows);
                    System.out.println(JsonUtil.toJsonWithPretty(rowList));
                    assertEquals(1, rowList.size());
                    assertTrue(rowList.get(0).containsKey("_id"));
                }
                try (ResultSet rows = statement.executeQuery("select * from " + index + " limit 1")) {
                    List<Map<String, Object>> rowList = JdbcUtil.getRows(rows);
                    System.out.println(JsonUtil.toJsonWithPretty(rowList));
                    assertFalse(rowList.isEmpty());
                }
            }
        }
    }
}
