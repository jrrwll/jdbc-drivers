package org.dreamcat.elasticsearch.client;

import org.dreamcat.common.json.JsonUtil;
import org.dreamcat.elasticsearch.config.EsConfig;
import org.dreamcat.elasticsearch.sql.SqlSupport;
import org.dreamcat.elasticsearch.sql.SqlUpdate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

/** Executes primary-key updates through the Elasticsearch _update API. */
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
