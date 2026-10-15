package org.dreamcat.elasticsearch.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SearchResult {

    public final List<String> columns;
    public final List<Map<String, Object>> rows;

    public SearchResult(List<String> columns, List<Map<String, Object>> rows) {
        this.columns = new ArrayList<>(columns);
        this.rows = rows;
    }
}
