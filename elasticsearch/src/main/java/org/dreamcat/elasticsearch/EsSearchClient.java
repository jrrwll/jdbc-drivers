package org.dreamcat.elasticsearch;

import org.dreamcat.common.json.JsonUtil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EsSearchClient {

    private final EsHttpClient httpClient;
    private final EsConfig config;

    EsSearchClient(EsConfig config) {
        this.config = config;
        this.httpClient = new EsHttpClient(config);
    }

    SearchResult search(SqlQuery query) throws SQLException {
        if (!config.allowsIndex(query.index))
            throw new SQLException("Index is not allowed by the JDBC URL: " + query.index);
        String json = httpClient.postJson("/" + query.index + "/_search", JsonUtil.toJson(query.body));
        return parseResult(json, query);
    }

    @SuppressWarnings("unchecked")
    private static SearchResult parseResult(String json, SqlQuery query) throws SQLException {
        final Map<String, Object> root;
        try {
            root = JsonUtil.fromJsonObject(json);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch response", e);
        }
        Object hitsObject = root.get("hits");
        if (!(hitsObject instanceof Map)) throw new SQLException("Elasticsearch response has no hits object");
        Object hitListObject = ((Map<?, ?>) hitsObject).get("hits");
        if (!(hitListObject instanceof Collection)) throw new SQLException("Elasticsearch response has no hits list");
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> dynamicColumns = new LinkedHashSet<>();
        for (Object hitObject : (Collection<?>) hitListObject) {
            if (!(hitObject instanceof Map)) continue;
            Object sourceObject = ((Map<?, ?>) hitObject).get("_source");
            Map<String, Object> source =
                    sourceObject instanceof Map ? (Map<String, Object>) sourceObject : Collections.emptyMap();
            Map<String, Object> row = new LinkedHashMap<>();
            if (query.allColumns) {
                flatten(source, "", row);
                dynamicColumns.addAll(row.keySet());
            } else {
                for (String column : query.columns) {
                    row.put(column,
                            "_id".equals(column) ? ((Map<?, ?>) hitObject).get("_id") : valueAt(source, column));
                }
            }
            rows.add(row);
        }
        List<String> columns = query.allColumns ? new ArrayList<>(dynamicColumns) : query.columns;
        if (query.allColumns) {
            for (Map<String, Object> row : rows) {
                for (String column : columns) row.putIfAbsent(column, null);
            }
        }
        return new SearchResult(columns, rows);
    }

    @SuppressWarnings("unchecked")
    private static Object valueAt(Map<String, Object> source, String path) {
        if (source.containsKey(path)) return source.get(path);
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> source, String prefix, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) flatten((Map<String, Object>) entry.getValue(), name, result);
            else result.put(name, entry.getValue());
        }
    }
}
