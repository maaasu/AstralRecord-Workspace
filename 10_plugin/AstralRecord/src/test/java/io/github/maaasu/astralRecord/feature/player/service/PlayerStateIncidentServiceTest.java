package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerStateFailure;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlayerStateIncidentServiceTest {
    @TempDir Path directory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 保存不能の診断と再ロード
     * 検証契約: 再ログイン前の退出清掃が後から実行されても、現セッションのUUIDと履歴を診断へ残す。
     */
    @Test
    void oldQuitCleanupCannotEraseRejoinedSessionEvidence() throws Exception {
        JavaPlugin plugin = plugin(directory);
        PlayerStateIncidentService service = new PlayerStateIncidentService(plugin, ignored -> true, ignored -> { });
        var ast = mock(io.github.maaasu.astralRecord.feature.player.model.AstPlayer.class, RETURNS_DEEP_STUBS);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(ast.getBukkit().getUniqueId()).thenReturn(playerId);
        when(ast.getBukkit().getName()).thenReturn("diagnostic-player");
        when(ast.getAccount().getUuid()).thenReturn(accountId);
        io.github.maaasu.astralRecord.feature.player.AstPlayerCache.put(ast);
        try {
            service.onLoaded(ast);
            var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
            var bukkit = ast.getBukkit();
            when(quit.getPlayer()).thenReturn(bukkit);
            service.onQuit(quit);
            var cleanup = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(plugin.getServer().getScheduler()).runTaskLater(eq(plugin), cleanup.capture(), anyLong());
            service.onLoaded(ast);
            cleanup.getValue().run();
            service.onFailure(new PlayerStateFailure(accountId, UUID.randomUUID(), Instant.now(), "AUTO", 1,
                "{}", new IllegalStateException("conflict")));
            try (var files = Files.list(directory.resolve("player-state-incidents"))) {
                var report = JsonParser.parseString(Files.readString(files.findFirst().orElseThrow())).getAsJsonObject();
                assertEquals(playerId.toString(), report.get("playerUuid").getAsString());
                assertFalse(report.getAsJsonArray("recentEvents").isEmpty());
            }
        } finally {
            io.github.maaasu.astralRecord.feature.player.AstPlayerCache.remove(playerId);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 保存不能の診断と再ロード
     * 検証契約: 診断ファイルはUTF-8で元payloadと完全なHTTP拒否理由を保存し、同時刻の別snapshotと衝突しない。
     */
    @Test
    void writesFullEvidenceWithoutOverwritingAnotherIncidentAtSameTimestamp() throws Exception {
        JavaPlugin plugin = plugin(directory);
        PlayerStateIncidentService service = new PlayerStateIncidentService(plugin, ignored -> true, ignored -> { });
        UUID accountId = UUID.randomUUID();
        String body = "不整合".repeat(300);
        InventoryApiException failure = new InventoryApiException("POST", "/snapshot", 409, body);
        failure.addSuppressed(new IllegalStateException("ack lookup failed"));
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        service.onFailure(new PlayerStateFailure(accountId, UUID.randomUUID(), now, "AUTO", 1, "{\"value\":123}", failure));
        service.onFailure(new PlayerStateFailure(accountId, UUID.randomUUID(), now, "AUTO", 1, "{\"value\":123}", failure));
        try (var files = Files.list(directory.resolve("player-state-incidents"))) {
            var reports = files.toList();
            assertEquals(2, reports.size());
            var report = JsonParser.parseString(Files.readString(reports.getFirst())).getAsJsonObject();
            assertEquals(body, report.get("httpResponse").getAsString());
            assertTrue(report.get("exception").getAsString().contains("ack lookup failed"));
            assertEquals(123, report.getAsJsonObject("rejectedSnapshot").get("value").getAsInt());
            assertEquals(accountId.toString(), report.get("accountId").getAsString());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 保存不能の診断と再ロード
     * 検証契約: 診断フォルダへ書き込めない場合もメインスレッドの復旧要求を登録する。
     */
    @Test
    void schedulesRecoveryEvenWhenDiagnosticDirectoryCannotBeCreated() throws Exception {
        Files.writeString(directory.resolve("player-state-incidents"), "occupied");
        JavaPlugin plugin = plugin(directory);
        PlayerStateIncidentService service = new PlayerStateIncidentService(plugin, ignored -> true, ignored -> { });
        service.onFailure(new PlayerStateFailure(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), "AUTO", 1,
            "{}", new IllegalStateException("invalid acknowledgement")));
        verify(plugin.getServer().getScheduler()).runTask(eq(plugin), any(Runnable.class));
    }

    private static JavaPlugin plugin(Path directory) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        return plugin;
    }
}
