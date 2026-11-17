package io.github.maaasu.astralRecord.feature.worldplacement.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldPlacementRepositoryTest {
    /**
     * スラッシュを含む物理ワールド名と既存ウェイストーンIDを保持し、無効行は除外し、未知アイコンと空の作成者は既定表示へ戻す。
     */
    @Test
    void keepsPhysicalWorldAndWaystoneIdentityButOmitsDisabledPlacement() {
        String json = """
            {"schemaVersion":1,"revision":"42","placements":[
              {"placementId":"s1","kind":"spawner","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":"wolf","x":1,"y":64,"z":2,"yaw":0,"pitch":0,"enabled":true,"waystone":null},
              {"placementId":"n1","kind":"npc","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":"guide","x":1.5,"y":65,"z":2.5,"yaw":90,"pitch":0,"enabled":false,"waystone":null},
              {"placementId":"w1","kind":"waystone","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":null,"x":3,"y":64,"z":4,"yaw":0,"pitch":0,"enabled":true,
                "waystone":{"id":"ws-20260801163308-waystone","name":"拠点","lockEnabled":false,"unlockGold":0,"icon":"UNKNOWN_ICON","createdAt":"2026-08-01T16:33:08.928898200Z","createdBy":""}}
            ]}
            """;

        WorldPlacementRepository.Snapshot snapshot = WorldPlacementRepository.parseSnapshot(json);

        assertEquals("42", snapshot.revision());
        assertEquals("plugins/AstralRecord/worlds/base/starlit_nox", snapshot.spawners().getFirst().worldName());
        assertEquals(0, snapshot.npcs().size());
        assertEquals("ws-20260801163308-waystone", snapshot.waystones().getFirst().id());
        assertEquals(Instant.parse("2026-08-01T16:33:08.928898200Z"), snapshot.waystones().getFirst().createdAt());
        assertEquals("system", snapshot.waystones().getFirst().createdBy());
        assertEquals(Material.BEACON, snapshot.waystones().getFirst().displayIcon());
    }

    /**
     * 不正行が一件でもあれば一括スナップショットを拒否し、部分的な公開値を返さない。
     */
    @Test
    void rejectsEntireSnapshotForInvalidDisabledRow() {
        String json = """
            {"schemaVersion":1,"revision":"1","placements":[
              {"placementId":"s1","kind":"spawner","worldId":"base","worldName":"world","templateId":"wolf","x":1,"y":64,"z":2,"yaw":0,"pitch":0,"enabled":true,"waystone":null},
              {"placementId":"x1","kind":"unknown","worldId":"base","worldName":"world","templateId":null,"x":0,"y":0,"z":0,"yaw":0,"pitch":0,"enabled":false,"waystone":null}
            ]}
            """;

        assertThrows(IllegalStateException.class, () -> WorldPlacementRepository.parseSnapshot(json));
    }

    /**
     * 現行 Dev revision を指定して単件更新を送り、更新キーは書き込みだけに含める。
     */
    @Test
    void writesOneWaystoneWithCurrentDevRevision() throws IOException {
        var requests = new ConcurrentLinkedQueue<RecordedRequest>();
        assertTrue(registerAgainstLoopback(200, true, requests));

        var calls = List.copyOf(requests);
        assertEquals(2, calls.size());
        var read = calls.getFirst();
        var write = calls.getLast();
        assertEquals("GET", read.method());
        assertEquals("/api/world-placements/dev", read.path());
        assertEquals("test-api-key", read.apiKey());
        assertNull(read.writeKey());
        assertEquals("PUT", write.method());
        assertEquals("test-api-key", write.apiKey());
        assertEquals("test-write-key", write.writeKey());

        var body = JsonParser.parseString(write.body()).getAsJsonObject();
        var placement = body.getAsJsonObject("placement");
        var waystone = placement.getAsJsonObject("waystone");
        assertEquals("42", body.get("expectedRevision").getAsString());
        assertEquals(read.path() + "/" + placement.get("placementId").getAsString(), write.path());
        assertEquals("logical-id", placement.get("worldId").getAsString());
        assertEquals("plugins/world", placement.get("worldName").getAsString());
        assertTrue(placement.get("templateId").isJsonNull());
        assertEquals("ws-test", waystone.get("id").getAsString());
        assertEquals("道標", waystone.get("name").getAsString());
        assertEquals(9_007_199_254_740_991L, waystone.get("unlockGold").getAsLong());
        assertTrue(waystone.get("icon").isJsonNull());
    }

    /**
     * revision 競合を成功とせず自動再送もしない。成功応答に登録行がなければ保存確認としない。
     */
    @Test
    void treatsConflictAndUnconfirmedResponseAsSeparateOutcomes() throws IOException {
        var requests = new ConcurrentLinkedQueue<RecordedRequest>();
        assertFalse(registerAgainstLoopback(409, false, requests));
        assertEquals(2, requests.size());
        requests.clear();
        assertThrows(IllegalStateException.class, () -> registerAgainstLoopback(200, false, requests));
        assertEquals(2, requests.size());
    }

    /**
     * チャンネルの API モードでは、更新キーを設定していてもゲーム内から登録できない。
     */
    @Test
    void refusesRegistrationInChannelsEvenWithWriteKey() {
        var repository = configuredRepository("channels", "", 30_000L);
        var error = assertThrows(IllegalStateException.class,
            () -> repository.createDevWaystone("logical-id", testWaystone()));
        assertEquals("Dev world placement write is disabled", error.getMessage());
    }

    /**
     * 応答ヘッダーを受信しても本文が完了しない配置要求を期限で取り消す。
     */
    @Test
    void timesOutWhenResponseBodyStallsAfterHeaders() throws IOException {
        var releaseResponse = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/world-placements/dev", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var repository = configuredRepository("dev", "http://127.0.0.1:" + server.getAddress().getPort(), 1000L);
            var error = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IllegalStateException.class,
                () -> repository.createDevWaystone("logical-id", testWaystone())));
            assertEquals("World placements request timed out", error.getMessage());
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    /** loopback で Dev の revision 取得と登録への応答を再現します。 */
    private static boolean registerAgainstLoopback(int writeStatus, boolean includeSavedPlacement,
            ConcurrentLinkedQueue<RecordedRequest> requests) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/world-placements/dev", exchange -> {
            try (exchange) {
                String method = exchange.getRequestMethod();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new RecordedRequest(method, exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Api-Key"),
                    exchange.getRequestHeaders().getFirst("X-World-Placement-Key"), body));
                boolean writing = method.equals("PUT");
                var snapshot = new JsonObject();
                snapshot.addProperty("schemaVersion", 1);
                snapshot.addProperty("revision", writing ? "43" : "42");
                var placements = new JsonArray();
                if (writing && includeSavedPlacement) {
                    placements.add(JsonParser.parseString(body).getAsJsonObject().get("placement"));
                }
                snapshot.add("placements", placements);
                byte[] response = snapshot.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(writing ? writeStatus : 200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var repository = configuredRepository("dev", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/",
                30_000L);
            return repository.createDevWaystone("logical-id", testWaystone());
        } finally {
            server.stop(0);
        }
    }

    /** 実行環境から独立した最小の API 設定を作ります。 */
    private static WorldPlacementRepository configuredRepository(String environment, String baseUrl, long timeoutMillis) {
        var plugin = mock(Plugin.class);
        var config = new YamlConfiguration();
        config.set("worldPlacements.mode", "api");
        config.set("worldPlacements.environment", environment);
        config.set("worldPlacements.baseUrl", baseUrl);
        config.set("worldPlacements.writeKey", "test-write-key");
        config.set("api.auth.apiKey", "test-api-key");
        config.set("api.timeout", timeoutMillis);
        when(plugin.getConfig()).thenReturn(config);
        return new WorldPlacementRepository(plugin);
    }

    /** 配置マスターを読み込まずテスト用の定義を作ります。 */
    private static WaystoneDefinition testWaystone() {
        return new WaystoneDefinition("ws-test", "道標", "plugins/world", -2, 64, 8, 45, -10,
            true, 9_007_199_254_740_991L, null, Instant.EPOCH, "test-admin");
    }

    private record RecordedRequest(String method, String path, String apiKey, String writeKey, String body) {}
}
