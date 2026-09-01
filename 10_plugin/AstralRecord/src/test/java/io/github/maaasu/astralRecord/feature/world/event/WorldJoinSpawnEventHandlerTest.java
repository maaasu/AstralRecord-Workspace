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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
            scheduledTasks.get(1).run();

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 4. 参加時の拠点スポーン転送
     * 検証契約: 初回の非同期転送後に対象ワールドへ到達していない場合は、参加後検証で1回だけ再転送し、最終確認を行う。
     */
    @Test
    void failedInitialJoinSpawnIsRetriedAfterPostJoinVerification() {
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
        World initialWorld = mock(World.class);
        World targetWorld = mock(World.class);
        UUID playerUuid = UUID.randomUUID();
        AtomicInteger teleportAttempts = new AtomicInteger();
        List<Runnable> joinTasks = new ArrayList<>();
        List<Runnable> verificationTasks = new ArrayList<>();
        List<Long> verificationDelays = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("relogin-player");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(initialWorld, targetWorld);
        when(server.getPlayer(playerUuid)).thenReturn(player);
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.resolveLoadedWorld(worldData)).thenReturn(targetWorld);
        when(worldService.teleportToSpawnAsync(player, worldData)).thenAnswer(invocation ->
            CompletableFuture.completedFuture(teleportAttempts.getAndIncrement() != 0)
        );
        doAnswer(invocation -> {
            joinTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            verificationTasks.add(invocation.getArgument(1, Runnable.class));
            verificationDelays.add(invocation.getArgument(2, Long.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        try (MockedStatic<Logger> ignoredLogger = mockStatic(Logger.class)) {
            handler.onPlayerJoin(event);
            joinTasks.getFirst().run();
            joinTasks.get(1).run();

            assertEquals(List.of(40L), verificationDelays);
            verificationTasks.getFirst().run();

            verify(worldService, times(2)).teleportToSpawnAsync(player, worldData);
            assertEquals(List.of(40L, 10L), verificationDelays);
            verificationTasks.get(1).run();
            verify(worldService, times(2)).teleportToSpawnAsync(player, worldData);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 4. 参加時の拠点スポーン転送
     * 検証契約: 初回転送 Future が例外完了した場合でも、参加後の検証予約を失わない。
     */
    @Test
    void exceptionalInitialJoinSpawnSchedulesVerification() {
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
        List<Runnable> joinTasks = new ArrayList<>();
        List<Runnable> verificationTasks = new ArrayList<>();
        List<Long> verificationDelays = new ArrayList<>();
        CompletableFuture<Boolean> failedTeleport = new CompletableFuture<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("exception-player");
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.teleportToSpawnAsync(player, worldData)).thenReturn(failedTeleport);
        doAnswer(invocation -> {
            joinTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            verificationTasks.add(invocation.getArgument(1, Runnable.class));
            verificationDelays.add(invocation.getArgument(2, Long.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        try (MockedStatic<Logger> ignoredLogger = mockStatic(Logger.class)) {
            handler.onPlayerJoin(event);
            joinTasks.getFirst().run();
            CompletableFuture.runAsync(() ->
                failedTeleport.completeExceptionally(new IllegalStateException("teleport failed"))
            ).join();
            assertEquals(0, verificationTasks.size());
            joinTasks.get(1).run();

            assertEquals(1, verificationTasks.size());
            assertEquals(List.of(40L), verificationDelays);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 4. 参加時の拠点スポーン転送
     * 検証契約: 初回転送と再試行がともに失敗した場合は、最大試行回数を超えて転送せず最終失敗を記録する。
     */
    @Test
    void failedInitialAndRetryStopAtMaximumTeleportAttempts() {
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
        World initialWorld = mock(World.class);
        World targetWorld = mock(World.class);
        UUID playerUuid = UUID.randomUUID();
        List<Runnable> joinTasks = new ArrayList<>();
        List<Runnable> verificationTasks = new ArrayList<>();
        List<Long> verificationDelays = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("failed-relogin-player");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(initialWorld);
        when(server.getPlayer(playerUuid)).thenReturn(player);
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.resolveLoadedWorld(worldData)).thenReturn(targetWorld);
        when(targetWorld.getName()).thenReturn("starlit_nox_runtime");
        when(worldService.teleportToSpawnAsync(player, worldData))
            .thenReturn(CompletableFuture.completedFuture(false));
        doAnswer(invocation -> {
            joinTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            verificationTasks.add(invocation.getArgument(1, Runnable.class));
            verificationDelays.add(invocation.getArgument(2, Long.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        try (MockedStatic<Logger> logger = mockStatic(Logger.class)) {
            handler.onPlayerJoin(event);
            joinTasks.getFirst().run();
            joinTasks.get(1).run();
            verificationTasks.getFirst().run();
            verificationTasks.get(1).run();

            verify(worldService, times(2)).teleportToSpawnAsync(player, worldData);
            assertEquals(List.of(40L, 10L), verificationDelays);
            logger.verify(() -> Logger.log(
                LogId.W_5753,
                "starlit_nox",
                targetWorld.getName(),
                12.0D,
                64.0D,
                -8.0D
            ), times(2));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 4. 参加時の拠点スポーン転送
     * 検証契約: 再接続後に古い Player インスタンスの検証が実行されても、新しいセッションへ再転送しない。
     */
    @Test
    void postJoinVerificationIgnoresReconnectedPlayerInstance() {
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
        Player oldPlayer = mock(Player.class);
        Player newPlayer = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        UUID playerUuid = UUID.randomUUID();
        List<Runnable> joinTasks = new ArrayList<>();
        List<Runnable> verificationTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(oldPlayer);
        when(oldPlayer.getName()).thenReturn("reconnected-player");
        when(oldPlayer.getUniqueId()).thenReturn(playerUuid);
        when(oldPlayer.isOnline()).thenReturn(true);
        when(server.getPlayer(playerUuid)).thenReturn(newPlayer);
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.teleportToSpawnAsync(oldPlayer, worldData))
            .thenReturn(CompletableFuture.completedFuture(true));
        doAnswer(invocation -> {
            joinTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            verificationTasks.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());

        WorldJoinSpawnEventHandler handler = new WorldJoinSpawnEventHandler(
            plugin,
            "starlit_nox",
            worldService
        );

        handler.onPlayerJoin(event);
        joinTasks.getFirst().run();
        joinTasks.get(1).run();
        verificationTasks.getFirst().run();

        verify(worldService).teleportToSpawnAsync(oldPlayer, worldData);
        verify(worldService, never()).resolveLoadedWorld(worldData);
    }
}
