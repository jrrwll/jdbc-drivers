package org.dreamcat.elasticsearch.client;

import org.dreamcat.common.json.JsonUtil;
import org.dreamcat.elasticsearch.config.EsConfig;
import org.dreamcat.elasticsearch.sql.SqlDelete;
import org.dreamcat.elasticsearch.sql.SqlInsert;
import org.dreamcat.elasticsearch.sql.SqlSupport;
import org.dreamcat.elasticsearch.sql.SqlUpdate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes document writes through the Elasticsearch _update and _bulk APIs. */
public final class EsUpdateClient {

    private final EsHttpClient httpClient;
    private final EsConfig config;

    public EsUpdateClient(EsConfig config) {
        this.config = config;
        this.httpClient = new EsHttpClient(config);
    }

    public int update(SqlUpdate update) throws SQLException {
        if (!config.allowsIndex(update.index))
            throw new SQLException("Index is not allowed by the JDBC URL: " + update.index);
        String path = "/" + update.index + "/_update/" + encodePathSegment(update.id);
        String json = httpClient.postJson(path, JsonUtil.toJson(SqlSupport.map("doc", update.doc)));
        return affectedRows(json);
    }

    public int insert(SqlInsert insert) throws SQLException {
        requireAllowedIndex(insert.index);
        StringBuilder body = new StringBuilder();
        for (SqlInsert.Row row : insert.rows) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (row.id != null) metadata.put("_id", row.id);
            body.append(JsonUtil.toJson(SqlSupport.map("create", metadata))).append('\n');
            body.append(JsonUtil.toJson(row.doc)).append('\n');
        }
        return bulk(insert.index, "create", body.toString(), insert.rows.size());
    }

    public int delete(SqlDelete delete) throws SQLException {
        requireAllowedIndex(delete.index);
        if (delete.ids.isEmpty()) return 0;
        StringBuilder body = new StringBuilder();
        for (String id : delete.ids) {
            body.append(JsonUtil.toJson(SqlSupport.map("delete", SqlSupport.map("_id", id)))).append('\n');
        }
        return bulk(delete.index, "delete", body.toString(), delete.ids.size());
    }

    private void requireAllowedIndex(String index) throws SQLException {
        if (!config.allowsIndex(index)) throw new SQLException("Index is not allowed by the JDBC URL: " + index);
    }

    private int bulk(String index, String action, String body, int expectedItems) throws SQLException {
        String json = httpClient.postJson("/" + index + "/_bulk", body);
        final Map<String, Object> root;
        try {
            root = JsonUtil.fromJsonObject(json);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch bulk response", e);
        }
        Object items = root == null ? null : root.get("items");
        if (!(items instanceof List) || ((List<?>) items).size() != expectedItems)
            throw new SQLException("Invalid Elasticsearch bulk response: unexpected item count");
        int affected = 0;
        SQLException failure = null;
        for (Object item : (List<?>) items) {
            Object result = item instanceof Map ? ((Map<?, ?>) item).get(action) : null;
            if (!(result instanceof Map)) throw new SQLException("Invalid Elasticsearch bulk item response");
            Map<?, ?> response = (Map<?, ?>) result;
            Object status = response.get("status");
            Object outcome = response.get("result");
            if (status instanceof Number && ((Number) status).intValue() >= 200
                    && ((Number) status).intValue() < 300 && response.get("error") == null
                    && ("create".equals(action) ? "created".equals(outcome) : "deleted".equals(outcome))) {
                affected++;
            } else if ("delete".equals(action) && status instanceof Number && ((Number) status).intValue() == 404
                    && "not_found".equals(outcome) && response.get("error") == null) {
                // Missing documents do not contribute to the affected-row count.
            } else {
                SQLException error = new SQLException("Elasticsearch bulk " + action + " failed for _id="
                        + response.get("_id") + " (status " + status + "): " + JsonUtil.toJson(response));
                if (failure == null) failure = error;
                else failure.setNextException(error);
            }
        }
        if (failure != null) {
            throw new SQLException("Elasticsearch bulk " + action + " failed; " + affected
                    + " document(s) succeeded: " + failure.getMessage(), failure);
        }
        return affected;
    }

    private static int affectedRows(String json) throws SQLException {
        final Map<String, Object> root;
        try {
            root = JsonUtil.fromJsonObject(json);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch update response", e);
        }
        Object result = root.get("result");
        if ("updated".equals(result) || "noop".equals(result)) return 1;
        if ("not_found".equals(result)) return 0;
        throw new SQLException("Unexpected Elasticsearch update result: " + result);
    }

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int c = item & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }
}
