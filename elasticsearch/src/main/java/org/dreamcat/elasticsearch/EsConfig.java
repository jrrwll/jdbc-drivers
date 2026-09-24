package org.dreamcat.elasticsearch;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

final class EsConfig {

    final String endpoint;
    final String urlPrefix;
    final String user;
    final String password;
    private final List<Pattern> indexPatterns;

    private EsConfig(String endpoint, String urlPrefix, String user, String password, List<Pattern> indexPatterns) {
        this.endpoint = endpoint;
        this.urlPrefix = urlPrefix;
        this.user = user;
        this.password = password;
        this.indexPatterns = indexPatterns;
    }

    static EsConfig parse(String jdbcUrl, Properties supplied) throws SQLException {
        String prefix = Driver.urlPrefix(jdbcUrl);
        if (prefix == null) throw new SQLException("Unsupported Elasticsearch JDBC URL prefix");
        String raw = jdbcUrl.substring(prefix.length());
        if (!raw.startsWith("//")) throw new SQLException(
                "Elasticsearch JDBC URL must use jdbc:es://host:port or jdbc:elasticsearch://host:port");
        int queryStart = raw.indexOf('?');
        String endpointPart = queryStart < 0 ? raw : raw.substring(0, queryStart);
        String query = queryStart < 0 ? null : raw.substring(queryStart + 1);
        if (query != null && query.indexOf('#') >= 0) throw new SQLException("JDBC URL fragments are not supported");
        URI uri;
        try {
            uri = new URI("http:" + endpointPart);
        } catch (Exception e) {
            throw new SQLException("Invalid Elasticsearch JDBC URL", e);
        }
        String path = uri.getPath();
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                || path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new SQLException("JDBC URL may contain only an Elasticsearch host and port");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) throw new SQLException("Elasticsearch host is required");
        Map<String, List<String>> values = parseQuery(query);
        List<Pattern> indexPatterns = new ArrayList<>();
        for (String index : values.getOrDefault("index", Collections.emptyList())) {
            if (index.isEmpty()) throw new SQLException("index URL parameter must not be empty");
            StringBuilder regex = new StringBuilder("^");
            String[] parts = index.split("%", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) regex.append(".*");
                regex.append(Pattern.quote(parts[i]));
            }
            indexPatterns.add(Pattern.compile(regex.append('$').toString()));
        }
        Properties info = supplied == null ? new Properties() : supplied;
        return new EsConfig("http://" + authority, prefix, property(info, values, "user"),
                property(info, values, "password"), Collections.unmodifiableList(indexPatterns));
    }

    boolean allowsIndex(String index) {
        if (indexPatterns.isEmpty()) return true;
        for (Pattern pattern : indexPatterns) {
            if (pattern.matcher(index).matches()) return true;
        }
        return false;
    }

    boolean hasIndexFilter() {
        return !indexPatterns.isEmpty();
    }

    private static String property(Properties info, Map<String, List<String>> urlValues, String name) {
        if (info.containsKey(name)) return info.getProperty(name);
        List<String> values = urlValues.get(name);
        return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private static Map<String, List<String>> parseQuery(String query) throws SQLException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return values;
        for (String item : query.split("&")) {
            if (item.isEmpty()) continue;
            int separator = item.indexOf('=');
            String key = separator < 0 ? item : item.substring(0, separator);
            String value = separator < 0 ? "" : item.substring(separator + 1);
            try {
                String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                String decodedValue = URLDecoder.decode("index".equals(decodedKey) ? escapeBarePercent(value) : value,
                        StandardCharsets.UTF_8.name());
                values.computeIfAbsent(decodedKey, ignored -> new ArrayList<>()).add(decodedValue);
            } catch (Exception e) {
                throw new SQLException("Invalid URL parameter", e);
            }
        }
        return values;
    }

    private static String escapeBarePercent(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && (i + 2 >= value.length() || !hex(value.charAt(i + 1)) || !hex(value.charAt(i + 2)))) {
                result.append("%25");
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static boolean hex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }
}
