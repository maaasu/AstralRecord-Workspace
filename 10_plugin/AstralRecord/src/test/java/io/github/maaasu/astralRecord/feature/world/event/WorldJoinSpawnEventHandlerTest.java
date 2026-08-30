package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldJoinSpawnEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: 参加時スポーン先が解決できる場合は、設定されたスポーン地点への非同期転送を開始する。
     */
    @Test
    void successfulJoinSpawnStartsTeleportAfterJoin() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        WorldService worldService = mock(WorldService.class);
        WorldMasterData worldData = new WorldMasterData(
            1,
            "starlit_nox",
            "Base",
            WorldType.BASE,
            "plugins/AstralRecord/worlds/base/starlit_nox",
            "plugins/AstralRecord/_world_instances/starlit_nox",
            true,
            false,
            0,
            false,
            false,
            false,
            false,
            WorldSpawnLocation.defaultLocation(),
            "",
            null,
            null,
            null
        );
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        List<Runnable> scheduledTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("player");
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.teleportToSpawnAsync(player, worldData))
            .thenReturn(CompletableFuture.completedFuture(true));
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(server.getScheduler()).thenReturn(scheduler);

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        handler.onPlayerJoin(event);

        assertEquals(1, scheduledTasks.size());
        scheduledTasks.getFirst().run();

        verify(worldService).teleportToSpawnAsync(player, worldData);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 4. 参加時の拠点スポーン転送
     * 検証契約: 拠点ワールドが未ロードでも、参加時転送はオンデマンド読込を行う転送サービスへ委譲する。
     */
    @Test
    void joinSpawnDelegatesToAsyncTeleportWhenBaseWorldIsNotLoaded() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        WorldService worldService = mock(WorldService.class);
        WorldMasterData worldData = new WorldMasterData(
            1,
            "starlit_nox",
            "Base",
            WorldType.BASE,
            "plugins/AstralRecord/worlds/base/starlit_nox",
            "plugins/AstralRecord/_world_instances/starlit_nox",
            false,
            false,
            0,
            false,
            false,
            false,
            false,
            WorldSpawnLocation.defaultLocation(),
            "",
            null,
            null,
            null
        );
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        List<Runnable> scheduledTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("relogin-player");
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.teleportToSpawnAsync(player, worldData))
            .thenReturn(CompletableFuture.completedFuture(true));
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(server.getScheduler()).thenReturn(scheduler);

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        handler.onPlayerJoin(event);
        scheduledTasks.getFirst().run();

        verify(worldService).teleportToSpawnAsync(player, worldData);
        verify(worldService, never()).resolveSpawnLocation(worldData);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_5-例外・ログ・運用.md
     * 章・見出し: # 17_5-例外・ログ・運用 > ## ログ・メッセージ
     * 検証契約: 参加時スポーン転送が失敗した場合、W_5753 の world 引数へ解決済み Bukkit world 名を記録する。
     */
    @Test
    void failedJoinSpawnLogsResolvedBukkitWorldName() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        WorldService worldService = mock(WorldService.class);
        WorldMasterData worldData = new WorldMasterData(
            1,
            "starlit_nox",
            "Base",
            WorldType.BASE,
            "plugins/AstralRecord/worlds/base/starlit_nox",
            "plugins/AstralRecord/_world_instances/starlit_nox",
            true,
            false,
            0,
            false,
            false,
            false,
            false,
            new WorldSpawnLocation(12.0D, 64.0D, -8.0D, 0.0F, 0.0F),
            "",
            null,
            null,
            null
        );
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        World targetWorld = mock(World.class);
        List<Runnable> scheduledTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("relogin-player");
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.teleportToSpawnAsync(player, worldData))
            .thenReturn(CompletableFuture.completedFuture(false));
        when(worldService.resolveLoadedWorld(worldData)).thenReturn(targetWorld);
        when(targetWorld.getName()).thenReturn("starlit_nox_runtime");
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(server.getScheduler()).thenReturn(scheduler);

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        try (MockedStatic<Logger> logger = mockStatic(Logger.class)) {
            handler.onPlayerJoin(event);
            scheduledTasks.getFirst().run();

            logger.verify(() -> Logger.log(
                LogId.W_5753,
                "starlit_nox",
                "starlit_nox_runtime",
                12.0D,
                64.0D,
                -8.0D
            ));
        }
    }
}
