package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.BaseMusicService;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldJoinSpawnEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 検証契約: 参加時スポーン転送の成功後は、PlayerChangedWorldEvent の有無に依存せず拠点音楽を再同期する。
     */
    @Test
    void successfulJoinSpawnRefreshesBaseMusicAfterTeleport() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        WorldService worldService = mock(WorldService.class);
        BaseMusicService baseMusicService = mock(BaseMusicService.class);
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
        UUID playerId = UUID.randomUUID();
        List<Runnable> scheduledTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("player");
        when(player.isOnline()).thenReturn(true);
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
            worldService,
            baseMusicService
        );

        handler.onPlayerJoin(event);

        assertEquals(1, scheduledTasks.size());
        scheduledTasks.getFirst().run();

        verify(baseMusicService, never()).refreshPlayer(player);
        assertEquals(2, scheduledTasks.size());

        scheduledTasks.get(1).run();

        verify(baseMusicService).refreshPlayer(player);
    }
}
