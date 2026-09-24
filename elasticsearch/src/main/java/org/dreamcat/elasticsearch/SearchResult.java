package org.dreamcat.elasticsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SearchResult {

    final List<String> columns;
    final List<Map<String, Object>> rows;

    SearchResult(List<String> columns, List<Map<String, Object>> rows) {
        this.columns = new ArrayList<>(columns);
        this.rows = rows;
    }
}
