package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.event.world.WorldLoadEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NpcPlacementWorldEventHandlerTest extends MockBukkitTestBase {

    @Test
    void worldLoadDefersSpawnLoadedWorldsByConfiguredDelay() {
        var world = server().addSimpleWorld("npc_world");
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        NpcPlacementService placementService = mock(NpcPlacementService.class);
        NpcPlacementWorldEventHandler handler = new NpcPlacementWorldEventHandler(plugin, placementService);

        handler.onWorldLoad(new WorldLoadEvent(world));

        verify(placementService, never()).spawnLoadedWorlds();

        server().getScheduler().performTicks(19);

        verify(placementService, never()).spawnLoadedWorlds();

        server().getScheduler().performTicks(1);

        verify(placementService).spawnLoadedWorlds();
    }

    @Test
    void worldLoadRetriesWhilePendingPlacementsRemain() {
        var world = server().addSimpleWorld("npc_world");
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        NpcPlacementService placementService = mock(NpcPlacementService.class);
        NpcPlacementWorldEventHandler handler = new NpcPlacementWorldEventHandler(plugin, placementService);

        when(placementService.hasPendingPlacements()).thenReturn(true, true, false);

        handler.onWorldLoad(new WorldLoadEvent(world));

        server().getScheduler().performTicks(60);

        verify(placementService, times(3)).spawnLoadedWorlds();
        verify(placementService, times(3)).hasPendingPlacements();
    }

    @Test
    void worldLoadDoesNotRespawnOtherWorldPlacements() {
        var world = server().addSimpleWorld("npc_world");
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobService mobService = mock(MobService.class);
        NpcPlacementRepository repository = mock(NpcPlacementRepository.class);
        NpcPlacementService placementService = new NpcPlacementService(mobService, repository);
        NpcPlacementWorldEventHandler handler = new NpcPlacementWorldEventHandler(plugin, placementService);

        when(mobService.spawn(anyString(), any(Location.class))).thenReturn(mock(MobInstance.class));
        when(repository.loadAll()).thenReturn(List.of(
                new NpcPlacement("npc_shopkeeper", "other_world", 10.5D, 64.0D, -3.0D, 90.0F, 0.0F)
        ));

        placementService.loadAll();
        handler.onWorldLoad(new WorldLoadEvent(world));

        server().getScheduler().performTicks(100);

        verify(mobService, never()).spawn(anyString(), any(Location.class));
    }

    @Test
    void worldLoadSpawnsStartupPlacementsAfterWorldBecomesAvailable() {
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobService mobService = mock(MobService.class);
        NpcPlacementRepository repository = mock(NpcPlacementRepository.class);
        NpcPlacementService placementService = new NpcPlacementService(mobService, repository);
        NpcPlacementWorldEventHandler handler = new NpcPlacementWorldEventHandler(plugin, placementService);

        when(mobService.spawn(anyString(), any(Location.class))).thenReturn(mock(MobInstance.class));
        when(repository.loadAll()).thenReturn(List.of(
                new NpcPlacement("npc_shopkeeper", "startup_world", 10.5D, 64.0D, -3.0D, 90.0F, 0.0F)
        ));

        placementService.loadAll();
        verify(mobService, never()).spawn(anyString(), any(Location.class));

        server().addSimpleWorld("startup_world");

        handler.onWorldLoad(new WorldLoadEvent(server().getWorld("startup_world")));
        verify(mobService, never()).spawn(anyString(), any(Location.class));

        server().getScheduler().performTicks(20);

        verify(mobService).spawn(anyString(), argThat(location ->
                location.getWorld() != null
                        && "startup_world".equals(location.getWorld().getName())
                        && location.getX() == 10.5D
                        && location.getY() == 64.0D
                        && location.getZ() == -3.0D
        ));
    }
}
