package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
        Location spawnLocation = mock(Location.class);
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        List<Runnable> scheduledTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(event.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("player");
        when(worldService.getById("starlit_nox")).thenReturn(worldData);
        when(worldService.resolveSpawnLocation(worldData)).thenReturn(spawnLocation);
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
}
