package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SkillTreeRuntimeRepositoryTest {
    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 複数accountは専用runtimeキーを付けた1要求で同期し、未変更viewだけ全体を省略してSHA-256とsessionを送る。
     */
    @Test
    void batchPreservesAuthorityAndHashesOnlyOmittedAcknowledgedViews() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            JsonObject view = new JsonObject();
            view.addProperty("definitionGenerationId", "a".repeat(64));
            view.addProperty("playerStateVersion", 1);
            view.addProperty("evaluationFingerprint", "b".repeat(64));
            view.addProperty("editEligible", true);
            List<SkillTreeRuntimeRepository.AccountHeartbeat> items = List.of(
                    f.item(UUID.randomUUID(), view, false), f.item(UUID.randomUUID(), view, true),
                    f.item(UUID.randomUUID(), null, false));
            var results = f.repository.heartbeatAccounts("rpg", f.boot, items);
            assertEquals(3, results.size());
            assertTrue(results.values().stream().allMatch(SkillTreeRuntimeRepository.AccountHeartbeatResult::renewed));
            assertEquals(1, f.requests.size());
            var request = f.requests.getFirst();
            assertEquals("runtime-secret", request.key());
            JsonArray sent = request.body().getAsJsonArray("items");
            assertTrue(sent.get(0).getAsJsonObject().getAsJsonObject("view").has("view"));
            JsonObject unchanged = sent.get(1).getAsJsonObject();
            assertFalse(unchanged.getAsJsonObject("view").has("view"));
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(view.toString().getBytes(StandardCharsets.UTF_8))),
                    unchanged.get("viewContentHash").getAsString());
            assertEquals(f.boot.toString(), unchanged.getAsJsonObject("session").get("serverSessionId").getAsString());
            assertEquals(f.session.token(), unchanged.getAsJsonObject("session").get("accountLeaseToken").getAsString());
            assertFalse(sent.get(2).getAsJsonObject().has("view"));
            assertFalse(items.getFirst().toString().contains(f.session.token()));
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: APIがbatch/ACK未対応なら既存session更新・全体view・pendingへ戻り、次周期でも処理権限を維持する。
     */
    @Test
    void olderApiFallsBackAndAvoidsRepeatedUnsupportedEndpointProbes() throws Exception {
        try (Fixture f = new Fixture(true, false)) {
            JsonObject view = new JsonObject();
            var item = f.item(UUID.randomUUID(), view, true);
            for (int i = 0; i < 2; i++) {
                var result = f.repository.heartbeatAccounts("rpg", f.boot, List.of(item)).get(item.accountId());
                assertTrue(result.renewed()); assertTrue(result.viewAccepted()); assertTrue(result.operations().isEmpty());
            }
            assertEquals(8, f.requests.size());
            assertEquals(1, f.requests.stream().filter(r -> r.path().endsWith("/accounts/heartbeat")).count());
            assertEquals(1, f.requests.stream().filter(r -> r.path().endsWith("/view/ack")).count());
            assertEquals(2, f.requests.stream().filter(r -> r.path().endsWith("/sessions")).count());
            assertEquals(2, f.requests.stream().filter(r -> r.path().endsWith("/view") && r.body().has("view")).count());
            assertTrue(f.requests.stream().allMatch(r -> "runtime-secret".equals(r.key())));
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 入力accountが欠落した応答は一部成功のACKを採用せず例外となる。
     */
    @Test
    void incompleteBatchResponseIsNotAcknowledged() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            assertThrows(IllegalStateException.class, () -> f.repository.heartbeatAccounts("rpg", f.boot,
                    List.of(f.item(UUID.randomUUID(), null, false))));
        }
    }

    private record Observed(String path, String key, JsonObject body) { }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<Observed> requests = Collections.synchronizedList(new ArrayList<>());
        final UUID boot = UUID.randomUUID();
        final SkillTreeRuntimeRepository.AccountSession session = new SkillTreeRuntimeRepository.AccountSession(UUID.randomUUID(), "c".repeat(64));
        final SkillTreeRuntimeRepository repository = new SkillTreeRuntimeRepository();
        final MockedStatic<ConfigProperties> configs;
        final MockedStatic<ApiRequestUtil> api;
        Fixture(boolean oldApi, boolean incomplete) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject body = text.isEmpty() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
                requests.add(new Observed(path, exchange.getRequestHeaders().getFirst("X-SkillTree-Runtime-Key"), body));
                int status = 200; String response = "{}";
                if (path.endsWith("/accounts/heartbeat")) {
                    if (oldApi) status = 404;
                    else {
                        JsonArray values = new JsonArray();
                        if (!incomplete) for (var input : body.getAsJsonArray("items")) {
                            JsonObject value = new JsonObject();
                            value.add("accountId", input.getAsJsonObject().get("accountId"));
                            value.addProperty("renewed", true); value.addProperty("viewAccepted", input.getAsJsonObject().has("view"));
                            value.add("operations", new JsonArray()); values.add(value);
                        }
                        response = values.toString();
                    }
                } else if (oldApi && path.endsWith("/view/ack")) status = 405;
                else if (path.endsWith("/operations")) response = "[]";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            ConfigProperties config = mock(ConfigProperties.class);
            when(config.getApiSkillTreeRuntimeKey()).thenReturn("runtime-secret");
            configs = mockStatic(ConfigProperties.class);
            configs.when(ConfigProperties::getInstance).thenReturn(config);
            api = mockStatic(ApiRequestUtil.class);
            api.when(() -> ApiRequestUtil.buildRequestBuilder(anyString())).thenAnswer(call -> HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + call.getArgument(0))));
            api.when(ApiRequestUtil::sharedClient).thenReturn(HttpClient.newHttpClient());
        }
        SkillTreeRuntimeRepository.AccountHeartbeat item(UUID account, JsonObject view, boolean unchanged) {
            return new SkillTreeRuntimeRepository.AccountHeartbeat(account, session, "a".repeat(64), view, 2, unchanged);
        }
        @Override public void close() { api.close(); configs.close(); server.stop(0); }
    }
}
