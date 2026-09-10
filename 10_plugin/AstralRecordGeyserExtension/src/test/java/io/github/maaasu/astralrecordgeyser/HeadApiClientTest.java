package io.github.maaasu.astralrecordgeyser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HeadApiClientTest {
    @Test
    void readsAuthenticatedCatalogAndRejectsHttpFailuresAndRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedKey = new AtomicReference<>();
        server.createContext("/api/geyser/heads", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] json = "{\"textures\":[],\"playerUuids\":[\"01234567-89ab-cdef-0123-456789abcdef\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length);
            try (var output = exchange.getResponseBody()) { output.write(json); }
        });
        server.createContext("/unauthorized", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/api/geyser/heads");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HeadCatalog catalog = HeadApiClient.fetch(new HeadApiConfig(URI.create(base + "/api/geyser/heads"), "test-key", Duration.ofSeconds(2)));
            assertEquals("test-key", receivedKey.get());
            assertEquals(1, catalog.playerUuids().size());
            for (String path : new String[]{"/unauthorized", "/redirect"}) {
                assertThrows(IOException.class, () -> HeadApiClient.fetch(new HeadApiConfig(URI.create(base + path), "test-key", Duration.ofSeconds(2))));
            }
        } finally { server.stop(0); }
    }
}
