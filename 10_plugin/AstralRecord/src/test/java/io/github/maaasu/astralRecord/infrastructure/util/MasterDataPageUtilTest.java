package io.github.maaasu.astralRecord.infrastructure.util;

import com.sun.net.httpserver.HttpServer;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class MasterDataPageUtilTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-リポジトリ.md
     * 章・見出し: # 04_3-リポジトリ > ## 6. 詳細マスタ分割取得
     * 検証契約: 101件のitem詳細は100件と1件の2リクエストで取得し、API提供の単件本文hashを保持する。
     */
    @Test
    void loads101ItemsWithTwoRequestsAndPreservesDetailHash() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        withServer((path, query) -> {
            queries.add(query);
            int start = query.contains("offset=100") ? 100 : 0;
            int end = start == 0 ? 100 : 101;
            String items = IntStream.range(start, end).mapToObj(i -> entry("item" + i)).collect(Collectors.joining(","));
            return new Reply(200, page("g1", 101, items));
        }, () -> {
            var items = new ItemRepository().findAllDetails();
            assertEquals(101, items.size());
            assertEquals("item0", items.getFirst().getId());
            assertEquals("material", items.getFirst().getCategory());
            assertEquals(HASH, items.getFirst().getResponseBodySha256());
            assertEquals(List.of("offset=0&limit=100", "offset=100&limit=100&generation=g1"), queries);
        });
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-リポジトリ.md
     * 章・見出し: # 04_3-リポジトリ > ## 6. 詳細マスタ分割取得
     * 検証契約: 途中ページで409になれば旧世代の途中結果を捨て、新世代の全ページだけを返す。
     */
    @Test
    void restartsAfterGenerationConflictWithoutMixingPartialResults() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withServer((path, query) -> switch (requests.incrementAndGet()) {
            case 1 -> new Reply(200, page("old", 2, entry("old_item")));
            case 2 -> new Reply(409, "");
            case 3 -> new Reply(200, page("new", 2, entry("new_a")));
            default -> new Reply(200, page("new", 2, entry("new_b")));
        }, () -> {
            var items = new ItemRepository().findAllDetails();
            assertEquals(List.of("new_a", "new_b"), items.stream().map(item -> item.getId()).toList());
            assertEquals(4, requests.get());
        });
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-リポジトリ.md
     * 章・見出し: # 04_3-リポジトリ > ## 6. 詳細マスタ分割取得
     * 検証契約: 404/405のみ旧一覧と単件詳細へfallbackし、単件HTTP本文のhashを維持する。
     */
    @Test
    void fallsBackOnlyForUnavailableEndpointOnOldApi() throws Exception {
        for (int status : new int[]{404, 405}) {
            AtomicInteger requests = new AtomicInteger();
            withServer((path, query) -> {
                requests.incrementAndGet();
                if (path.endsWith("/details")) return new Reply(status, "");
                if (path.equals("/api/item")) return new Reply(200, "[{\"id\":\"legacy\",\"category\":\"material\",\"name\":\"星\"}]");
                return new Reply(200, master("legacy"));
            }, () -> {
                var items = new ItemRepository().findAllDetails();
                assertEquals(1, items.size());
                assertEquals("legacy", items.getFirst().getId());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(master("legacy").getBytes(StandardCharsets.UTF_8))), items.getFirst().getResponseBodySha256());
                assertEquals(3, requests.get());
            });
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-リポジトリ.md
     * 章・見出し: # 04_3-リポジトリ > ## 6. 詳細マスタ分割取得
     * 検証契約: 500障害は旧APIへfallbackせずprepare失敗として伝播する。
     */
    @Test
    void propagatesServerFailureWithoutLegacyFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withServer((path, query) -> {
            requests.incrementAndGet();
            return new Reply(500, "");
        }, () -> {
            assertThrows(IOException.class, () -> new ItemRepository().findAllDetails());
            assertEquals(1, requests.get());
        });
    }

    private static final String HASH = "0123456789abcdef".repeat(4);
    private static String master(String id) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"category\":\"material\",\"name\":\"星\",\"icon\":\"PAPER\",\"rarity\":\"COMMON\"}";
    }
    private static String entry(String id) { return "{\"master\":" + master(id) + ",\"responseBodySha256\":\"" + HASH + "\"}"; }
    private static String page(String generation, int total, String items) {
        return "{\"generation\":\"" + generation + "\",\"total\":" + total + ",\"items\":[" + items + "]}";
    }
    private static void withServer(Responder responder, CheckedAction action) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Reply reply = responder.reply(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (MockedStatic<ApiRequestUtil> transport = mockStatic(ApiRequestUtil.class)) {
            transport.when(() -> ApiRequestUtil.buildRequestBuilder(anyString()))
                .thenAnswer(call -> HttpRequest.newBuilder(URI.create(base + call.getArgument(0, String.class))));
            transport.when(ApiRequestUtil::sharedClient).thenReturn(HttpClient.newHttpClient());
            action.run();
        } finally { server.stop(0); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private interface Responder { Reply reply(String path, String query); }
    private record Reply(int status, String body) { }
}
