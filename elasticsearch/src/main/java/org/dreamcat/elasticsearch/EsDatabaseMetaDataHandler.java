package org.dreamcat.elasticsearch;

import org.dreamcat.common.json.JsonUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class EsDatabaseMetaDataHandler implements InvocationHandler {

    private static final List<String> TABLE_COLUMNS = Arrays.asList(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION");
    private static final List<String> COLUMN_COLUMNS = Arrays.asList(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
            "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
            "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN");
    private static final List<String> PRIMARY_KEY_COLUMNS = Arrays.asList(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
    private static final List<String> INDEX_COLUMNS = Arrays.asList(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
            "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
            "CARDINALITY", "PAGES", "FILTER_CONDITION");

    private final Connection connection;
    private final EsConfig config;
    private final EsHttpClient http;
    private final String url;
    private Map<String, Object> rootInfo;

    EsDatabaseMetaDataHandler(Connection connection, EsConfig config) {
        this.connection = connection;
        this.config = config;
        this.http = new EsHttpClient(config);
        this.url = config.urlPrefix + config.endpoint.substring("http:".length());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ES7DatabaseMetaData");
        if (connection.isClosed()) throw new SQLException("Connection is closed");
        switch (method.getName()) {
            case "getCatalogs":
                return catalogs();
            case "getSchemas":
                return rows(Arrays.asList("TABLE_SCHEM", "TABLE_CATALOG"), Collections.emptyList());
            case "getTables":
                return tables((String) args[0], (String) args[1], (String) args[2], (String[]) args[3]);
            case "getColumns":
                return columns((String) args[0], (String) args[1], (String) args[2], (String) args[3]);
            case "getPrimaryKeys":
                return primaryKeys((String) args[0], (String) args[1], (String) args[2]);
            case "getIndexInfo":
                return indexes((String) args[0], (String) args[1], (String) args[2], (Boolean) args[3]);
            case "getConnection":
                return connection;
            case "getURL":
                return url;
            case "getDatabaseProductName":
                return "Elasticsearch";
            case "getDatabaseProductVersion":
                return databaseVersion();
            case "getDatabaseMajorVersion":
                return versionPart(0);
            case "getDatabaseMinorVersion":
                return versionPart(1);
            case "getDriverName":
                return "Elasticsearch JDBC Driver";
            case "getDriverVersion":
                return Driver.MAJOR_VERSION + "." + Driver.MINOR_VERSION;
            case "getDriverMajorVersion":
                return Driver.MAJOR_VERSION;
            case "getDriverMinorVersion":
                return Driver.MINOR_VERSION;
            case "getJDBCMajorVersion":
                return 4;
            case "getJDBCMinorVersion":
                return 2;
            case "isReadOnly":
                return true;
            case "supportsTransactions":
            case "supportsBatchUpdates":
            case "supportsGroupBy":
            case "supportsMultipleResultSets":
            case "supportsMultipleTransactions":
                return false;
            case "supportsResultSetType":
                return ((Integer) args[0]) == ResultSet.TYPE_FORWARD_ONLY;
            case "supportsResultSetConcurrency":
                return ((Integer) args[0]) == ResultSet.TYPE_FORWARD_ONLY
                        && ((Integer) args[1]) == ResultSet.CONCUR_READ_ONLY;
            case "getIdentifierQuoteString":
                return "\"";
            case "getSearchStringEscape":
                return "\\";
            case "getCatalogSeparator":
                return ".";
            case "isCatalogAtStart":
                return true;
            case "supportsCatalogsInDataManipulation":
            case "supportsCatalogsInTableDefinitions":
            case "supportsCatalogsInIndexDefinitions":
            case "supportsCatalogsInProcedureCalls":
            case "supportsCatalogsInPrivilegeDefinitions":
            case "supportsSchemasInDataManipulation":
            case "supportsSchemasInTableDefinitions":
            case "supportsSchemasInIndexDefinitions":
            case "supportsSchemasInProcedureCalls":
            case "supportsSchemasInPrivilegeDefinitions":
                return false;
            case "supportsMixedCaseIdentifiers":
            case "storesMixedCaseIdentifiers":
            case "supportsMixedCaseQuotedIdentifiers":
            case "storesMixedCaseQuotedIdentifiers":
                return true;
            case "storesUpperCaseIdentifiers":
            case "storesLowerCaseIdentifiers":
            case "storesUpperCaseQuotedIdentifiers":
            case "storesLowerCaseQuotedIdentifiers":
                return false;
            case "getCatalogTerm":
                return "cluster";
            case "getSchemaTerm":
                return "";
            case "getTableTypes":
                return rows(Collections.singletonList("TABLE_TYPE"),
                        Collections.singletonList(row("TABLE_TYPE", "TABLE")));
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                throw new SQLException("Not a wrapper for " + args[0]);
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                throw JdbcSupport.unsupported(method);
        }
    }

    private ResultSet catalogs() throws SQLException {
        return rows(Collections.singletonList("TABLE_CAT"), Collections.singletonList(row("TABLE_CAT", clusterName())));
    }

    private ResultSet tables(String catalog, String schema, String tablePattern, String[] types) throws SQLException {
        if (types != null && Arrays.stream(types).noneMatch("TABLE"::equalsIgnoreCase))
            return rows(TABLE_COLUMNS, Collections.emptyList());
        if (!catalogMatches(catalog) || !schemaPatternMatches(schema))
            return rows(TABLE_COLUMNS, Collections.emptyList());
        List<Map<String, Object>> tableRows = new ArrayList<>();
        for (Object entry : array(http.get("/_cat/indices?format=json&h=index"))) {
            if (!(entry instanceof Map)) continue;
            Object name = ((Map<?, ?>) entry).get("index");
            if (!(name instanceof String) || !config.allowsIndex((String) name)
                    || !matches((String) name, tablePattern)) continue;
            Map<String, Object> row = row("TABLE_NAME", name);
            row.put("TABLE_CAT", clusterName());
            row.put("TABLE_TYPE", "TABLE");
            tableRows.add(row);
        }
        return rows(TABLE_COLUMNS, tableRows);
    }

    private ResultSet columns(String catalog, String schema, String tablePattern, String columnPattern)
            throws SQLException {
        if (!catalogMatches(catalog) || !schemaPatternMatches(schema))
            return rows(COLUMN_COLUMNS, Collections.emptyList());
        List<Map<String, Object>> columnRows = new ArrayList<>();
        String exactTable = exactTableName(tablePattern);
        if (exactTable != null) {
            if (!validTable(exactTable) || !config.allowsIndex(exactTable)) return rows(COLUMN_COLUMNS, columnRows);
            addColumnsFromMappings(object(http.get("/" + exactTable + "/_mapping")), tablePattern, columnPattern,
                    columnRows);
        } else if (config.hasIndexFilter()) {
            for (Object entry : array(http.get("/_cat/indices?format=json&h=index"))) {
                if (!(entry instanceof Map)) continue;
                Object name = ((Map<?, ?>) entry).get("index");
                if (!(name instanceof String) || !validTable((String) name)
                        || !config.allowsIndex((String) name) || !matches((String) name, tablePattern)) continue;
                addColumnsFromMappings(object(http.get("/" + name + "/_mapping")), tablePattern, columnPattern,
                        columnRows);
            }
        } else {
            addColumnsFromMappings(object(http.get("/_mapping")), tablePattern, columnPattern, columnRows);
        }
        return rows(COLUMN_COLUMNS, columnRows);
    }

    private void addColumnsFromMappings(Map<String, Object> mappings, String tablePattern, String columnPattern,
            List<Map<String, Object>> columnRows) throws SQLException {
        for (Map.Entry<String, Object> index : mappings.entrySet()) {
            if (!config.allowsIndex(index.getKey()) || !matches(index.getKey(), tablePattern)
                    || !(index.getValue() instanceof Map)) continue;
            if (matches("_id", columnPattern)) {
                Map<String, Object> id = row("TABLE_NAME", index.getKey());
                id.put("TABLE_CAT", clusterName());
                id.put("COLUMN_NAME", "_id");
                id.put("DATA_TYPE", Types.VARCHAR);
                id.put("TYPE_NAME", "keyword");
                id.put("NULLABLE", DatabaseMetaData.columnNoNulls);
                id.put("IS_NULLABLE", "NO");
                id.put("ORDINAL_POSITION", 1);
                id.put("IS_AUTOINCREMENT", "NO");
                id.put("IS_GENERATEDCOLUMN", "NO");
                columnRows.add(id);
            }
            Map<?, ?> mapping = (Map<?, ?>) index.getValue();
            Object nested = mapping.get("mappings");
            if (!(nested instanceof Map)) continue;
            Object properties = ((Map<?, ?>) nested).get("properties");
            if (properties instanceof Map) addColumns(index.getKey(), clusterName(), "", (Map<?, ?>) properties,
                    columnPattern, new int[]{1}, columnRows);
        }
    }

    private ResultSet primaryKeys(String catalog, String schema, String table) throws SQLException {
        if (!catalogMatches(catalog) || !schemaMatches(schema) || !validTable(table) || !config.allowsIndex(table)) {
            return rows(PRIMARY_KEY_COLUMNS, Collections.emptyList());
        }
        Map<String, Object> mapping = object(http.get("/" + table + "/_mapping"));
        if (!mapping.containsKey(table)) return rows(PRIMARY_KEY_COLUMNS, Collections.emptyList());
        Map<String, Object> key = row("TABLE_NAME", table);
        key.put("TABLE_CAT", clusterName());
        key.put("COLUMN_NAME", "_id");
        key.put("KEY_SEQ", (short) 1);
        key.put("PK_NAME", "_id");
        return rows(PRIMARY_KEY_COLUMNS, Collections.singletonList(key));
    }

    private ResultSet indexes(String catalog, String schema, String table, boolean unique) throws SQLException {
        if (unique || !catalogMatches(catalog) || !schemaMatches(schema) || !validTable(table)
                || !config.allowsIndex(table)) {
            return rows(INDEX_COLUMNS, Collections.emptyList());
        }
        Map<String, Object> mapping = object(http.get("/" + table + "/_mapping"));
        Object indexObject = mapping.get(table);
        if (!(indexObject instanceof Map)) return rows(INDEX_COLUMNS, Collections.emptyList());
        Object mappings = ((Map<?, ?>) indexObject).get("mappings");
        if (!(mappings instanceof Map)) return rows(INDEX_COLUMNS, Collections.emptyList());
        Object properties = ((Map<?, ?>) mappings).get("properties");
        List<Map<String, Object>> indexRows = new ArrayList<>();
        if (properties instanceof Map) addIndexes(table, clusterName(), "", (Map<?, ?>) properties, indexRows);
        return rows(INDEX_COLUMNS, indexRows);
    }

    private static void addIndexes(String table, String catalog, String prefix, Map<?, ?> properties,
            List<Map<String, Object>> rows) {
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) continue;
            String name = prefix.isEmpty() ? (String) entry.getKey() : prefix + "." + entry.getKey();
            Map<?, ?> definition = (Map<?, ?>) entry.getValue();
            if (definition.get("type") instanceof String && !Boolean.FALSE.equals(definition.get("index"))) {
                Map<String, Object> row = row("TABLE_NAME", table);
                row.put("TABLE_CAT", catalog);
                row.put("NON_UNIQUE", true);
                row.put("INDEX_NAME", name);
                row.put("TYPE", DatabaseMetaData.tableIndexOther);
                row.put("ORDINAL_POSITION", (short) 1);
                row.put("COLUMN_NAME", name);
                rows.add(row);
            }
            Object childProperties = definition.get("properties");
            if (childProperties instanceof Map) addIndexes(table, catalog, name, (Map<?, ?>) childProperties, rows);
            Object multiFields = definition.get("fields");
            if (multiFields instanceof Map) addIndexes(table, catalog, name, (Map<?, ?>) multiFields, rows);
        }
    }

    private static void addColumns(String index, String catalog, String prefix, Map<?, ?> properties, String pattern,
            int[] ordinal, List<Map<String, Object>> rows) {
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) continue;
            String name = prefix.isEmpty() ? (String) entry.getKey() : prefix + "." + entry.getKey();
            Map<?, ?> definition = (Map<?, ?>) entry.getValue();
            Object type = definition.get("type");
            if (type instanceof String) ordinal[0]++;
            if (type instanceof String && matches(name, pattern)) {
                Map<String, Object> row = row("TABLE_NAME", index);
                row.put("TABLE_CAT", catalog);
                row.put("COLUMN_NAME", name);
                row.put("DATA_TYPE", jdbcType((String) type));
                row.put("TYPE_NAME", type);
                row.put("NULLABLE", DatabaseMetaData.columnNullableUnknown);
                row.put("IS_NULLABLE", "");
                row.put("ORDINAL_POSITION", ordinal[0]);
                row.put("IS_AUTOINCREMENT", "NO");
                row.put("IS_GENERATEDCOLUMN", "NO");
                rows.add(row);
            }
            Object childProperties = definition.get("properties");
            if (childProperties instanceof Map)
                addColumns(index, catalog, name, (Map<?, ?>) childProperties, pattern, ordinal, rows);
            Object multiFields = definition.get("fields");
            if (multiFields instanceof Map)
                addColumns(index, catalog, name, (Map<?, ?>) multiFields, pattern, ordinal, rows);
        }
    }

    private boolean catalogMatches(String catalog) throws SQLException {
        if (catalog == null || catalog.isEmpty()) return true;
        return catalog.equals(clusterName());
    }

    String clusterName() throws SQLException {
        Object name = rootInfo().get("cluster_name");
        if (!(name instanceof String)) throw new SQLException("Elasticsearch response has no cluster_name");
        return (String) name;
    }

    private String databaseVersion() throws SQLException {
        Object version = rootInfo().get("version");
        Object number = version instanceof Map ? ((Map<?, ?>) version).get("number") : null;
        if (!(number instanceof String)) throw new SQLException("Elasticsearch response has no version.number");
        return (String) number;
    }

    private int versionPart(int position) throws SQLException {
        String[] parts = databaseVersion().split("\\.");
        if (parts.length <= position) throw new SQLException("Invalid Elasticsearch version: " + databaseVersion());
        try {
            return Integer.parseInt(parts[position]);
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid Elasticsearch version: " + databaseVersion(), e);
        }
    }

    private Map<String, Object> rootInfo() throws SQLException {
        if (rootInfo == null) rootInfo = object(http.get("/"));
        return rootInfo;
    }

    // IDEA's catalog-as-schema mode passes the cluster as both catalog and schema.
    // Accept that alias on lookup while retaining the schema-less JDBC result rows.
    private boolean schemaPatternMatches(String schema) throws SQLException {
        return matches("", schema) || matches(clusterName(), schema);
    }

    private boolean schemaMatches(String schema) throws SQLException {
        return schema == null || schema.isEmpty() || schema.equals(clusterName());
    }

    private static boolean validTable(String table) {
        return table != null && table.matches("[A-Za-z0-9._-]+") && !".".equals(table) && !"..".equals(table);
    }

    // JDBC metadata patterns escape literal underscores with a backslash.
    // Unescaped '_' and '%' are wildcards; do not mistake them for exact names.
    private static String exactTableName(String pattern) {
        if (pattern == null) return null;
        StringBuilder name = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                name.append(c);
                escaped = false;
            } else if (c == '\\') escaped = true;
            else if (c == '%' || c == '_') return null;
            else name.append(c);
        }
        if (escaped) name.append('\\');
        return name.toString();
    }

    private static boolean matches(String value, String pattern) {
        if (pattern == null) return true;
        StringBuilder regex = new StringBuilder("^");
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaped = false;
            } else if (c == '\\') escaped = true;
            else if (c == '%') regex.append(".*");
            else if (c == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        if (escaped) regex.append(Pattern.quote("\\"));
        return Pattern.compile(regex.append('$').toString()).matcher(value).matches();
    }

    private static int jdbcType(String type) {
        switch (type) {
            case "long":
            case "unsigned_long":
                return Types.BIGINT;
            case "integer":
                return Types.INTEGER;
            case "short":
                return Types.SMALLINT;
            case "byte":
                return Types.TINYINT;
            case "float":
            case "half_float":
                return Types.FLOAT;
            case "double":
            case "scaled_float":
                return Types.DOUBLE;
            case "boolean":
                return Types.BOOLEAN;
            case "date":
            case "date_nanos":
                return Types.TIMESTAMP;
            case "binary":
                return Types.BINARY;
            case "text":
                return Types.LONGVARCHAR;
            default:
                return Types.VARCHAR;
        }
    }

    private static ResultSet rows(List<String> columns, List<Map<String, Object>> data) {
        return (ResultSet) Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                new EsResultSetHandler(new SearchResult(columns, data)));
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private static Map<String, Object> object(String json) throws SQLException {
        try {
            return JsonUtil.fromJsonObject(json);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch JSON object", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(String json) throws SQLException {
        try {
            return JsonUtil.fromJsonArray(json, Object.class);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch JSON array", e);
        }
    }
}
