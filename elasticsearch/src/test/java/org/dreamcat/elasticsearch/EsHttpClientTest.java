package org.dreamcat.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class EsHttpClientTest {

    @Test
    void preservesHttpStatusAndErrorBodyForGetAndPost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String detail = "{\"error\":{\"type\":\"index_not_found_exception\",\"reason\":\"no such index [不存在]\"}}";
        for (int status : new int[]{400, 401, 403, 404, 500}) {
            server.createContext("/status" + status, exchange -> {
                byte[] data = detail.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, data.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) {
                    output.write(data);
                }
            });
        }
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        server.createContext("/proxy", exchange -> {
            byte[] data = "<html>upstream unavailable</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, data.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(data);
            }
        });
        server.start();
        try {
            EsHttpClient client = new EsHttpClient(EsConfig.parse("jdbc:es7://127.0.0.1:"
                    + server.getAddress().getPort(), null));
            for (int status : new int[]{400, 401, 403, 404, 500}) {
                String path = "/status" + status;
                for (String method : new String[]{"GET", "POST"}) {
                    SQLException error = assertThrows(SQLException.class, () -> {
                        if ("GET".equals(method)) client.get(path);
                        else client.postJson(path, "{}");
                    });
                    assertTrue(error.getMessage().contains(method + " " + path));
                    assertTrue(error.getMessage().contains("HTTP " + status));
                    assertTrue(error.getMessage().contains(detail));
                    assertEquals(status, error.getErrorCode());
                    assertEquals(status == 401 || status == 403 ? "28000" : "HY000", error.getSQLState());
                }
            }
            SQLException empty = assertThrows(SQLException.class, () -> client.get("/empty"));
            assertEquals(502, empty.getErrorCode());
            assertTrue(empty.getMessage().contains("HTTP 502"));
            SQLException proxy = assertThrows(SQLException.class, () -> client.postJson("/proxy", "{}"));
            assertTrue(proxy.getMessage().contains("upstream unavailable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesNetworkFailureCauseInTopLevelMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        server.stop(0);
        EsHttpClient client = new EsHttpClient(EsConfig.parse("jdbc:es7://127.0.0.1:" + port, null));
        SQLException error = assertThrows(SQLException.class, () -> client.postJson("/records/_search", "{}"));
        assertEquals("08006", error.getSQLState());
        assertNotNull(error.getCause());
        assertTrue(error.getMessage().contains(error.getCause().getMessage()));
        assertTrue(error.getMessage().contains("/records/_search"));
    }
}
