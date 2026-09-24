package org.dreamcat.elasticsearch;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.dreamcat.common.codec.Base64Util;
import org.dreamcat.common.hc.httpclient.HcHttpFetcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

final class EsHttpClient {

    private final EsConfig config;
    private final HcHttpFetcher fetcher = new HcHttpFetcher();

    EsHttpClient(EsConfig config) {
        this.config = config;
    }

    String get(String path) throws SQLException {
        try (ClassicHttpResponse response = fetcher.get(config.endpoint + path, headers())) {
            return read(response, "GET", path);
        } catch (IOException e) {
            throw new SQLException("Elasticsearch GET request failed: " + path + ": " + e.getMessage(), "08006", e);
        }
    }

    String postJson(String path, String requestJson) throws SQLException {
        try (ClassicHttpResponse response = fetcher.postJson(config.endpoint + path, headers(), requestJson)) {
            return read(response, "POST", path);
        } catch (IOException e) {
            throw new SQLException("Elasticsearch POST request failed: " + path + ": " + e.getMessage(), "08006", e);
        }
    }

    private String read(ClassicHttpResponse response, String method, String path) throws SQLException {
        int status = response.getCode();
        String context = "Elasticsearch " + method + " " + path + " (HTTP " + status + ")";
        final String json;
        try {
            // HcHttpFetcher.string checks status first and discards error response bodies.
            json = response.getEntity() == null ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        } catch (IOException | ParseException e) {
            throw new SQLException(context + ": failed to read response: " + e.getMessage(), "08006", status, e);
        }
        if (status < 200 || status >= 300) {
            throw new SQLException(context + ": " + (json.isEmpty() ? response.getReasonPhrase() : json),
                    status == 401 || status == 403 ? "28000" : "HY000", status);
        }
        return json;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.user != null || config.password != null) {
            String credentials =
                    (config.user == null ? "" : config.user) + ":" + (config.password == null ? "" : config.password);
            headers.put("Authorization", "Basic " + Base64Util.encodeAsString(credentials));
        }
        return headers;
    }
}
