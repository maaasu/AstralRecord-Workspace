package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NpcPlacementServiceTest extends MockBukkitTestBase {

    @Test
    void loadAllSpawnsPlacementsInAlreadyLoadedWorlds() {
        server().addSimpleWorld("spawn_world");
        PluginMock plugin = testPlugin();
        MobService mobService = mock(MobService.class);
        NpcPlacementRepository repository = mock(NpcPlacementRepository.class);
        NpcPlacementService service = new NpcPlacementService(plugin, mobService, repository);

        when(mobService.spawn(anyString(), any(Location.class))).thenReturn(mock(MobInstance.class));
        when(repository.loadAll()).thenReturn(List.of(
                new NpcPlacement("npc_shopkeeper", "spawn_world", 10.5D, 64.0D, -3.0D, 90.0F, 0.0F),
                new NpcPlacement("npc_guard", "late_world", 5.0D, 70.0D, 8.0D, 0.0F, 0.0F)
        ));

        int loaded = service.loadAll();

        assertEquals(2, loaded);
        verify(mobService).spawn(eq("npc_shopkeeper"), argThat(location ->
                location.getWorld() != null
                        && "spawn_world".equals(location.getWorld().getName())
                        && location.getX() == 10.5D
                        && location.getY() == 64.0D
                        && location.getZ() == -3.0D
        ));
        verify(mobService, never()).spawn(eq("npc_guard"), any(Location.class));
    }

    @Test
    void spawnLoadedWorldsRespawnsPlacementsWhenWorldBecomesAvailableLater() {
        PluginMock plugin = testPlugin();
        MobService mobService = mock(MobService.class);
        NpcPlacementRepository repository = mock(NpcPlacementRepository.class);
        NpcPlacementService service = new NpcPlacementService(plugin, mobService, repository);

        when(mobService.spawn(anyString(), any(Location.class))).thenReturn(mock(MobInstance.class));
        when(repository.loadAll()).thenReturn(List.of(
                new NpcPlacement("npc_guard", "late_world", 5.0D, 70.0D, 8.0D, 0.0F, 0.0F)
        ));

        service.loadAll();
        verify(mobService, never()).spawn(anyString(), any(Location.class));

        server().addSimpleWorld("late_world");

        int spawned = service.spawnLoadedWorlds();

        assertEquals(1, spawned);
        verify(mobService).spawn(eq("npc_guard"), argThat(location ->
                location.getWorld() != null
                        && "late_world".equals(location.getWorld().getName())
                        && location.getX() == 5.0D
                        && location.getY() == 70.0D
                        && location.getZ() == 8.0D
        ));
    }

    @Test
    void removeByNpcIdDeletesAllPlacedEntriesAndUpdatesRepository() {
        server().addSimpleWorld("spawn_world");
        PluginMock plugin = testPlugin();
        MobService mobService = mock(MobService.class);
        NpcPlacementRepository repository = mock(NpcPlacementRepository.class);
        NpcPlacementService service = new NpcPlacementService(plugin, mobService, repository);

        MobInstance first = mock(MobInstance.class);
        when(first.instanceId()).thenReturn(java.util.UUID.randomUUID());
        MobInstance second = mock(MobInstance.class);
        when(second.instanceId()).thenReturn(java.util.UUID.randomUUID());
        when(mobService.spawn(anyString(), any(Location.class))).thenReturn(first, second);
        when(repository.loadAll()).thenReturn(List.of(
                new NpcPlacement("starter_shopkeeper", "spawn_world", 10.5D, 64.0D, -3.0D, 90.0F, 0.0F),
                new NpcPlacement("starter_shopkeeper", "spawn_world", 12.5D, 64.0D, -3.0D, 90.0F, 0.0F),
                new NpcPlacement("equipment_merchant", "spawn_world", 15.0D, 64.0D, -3.0D, 90.0F, 0.0F)
        ));

        service.loadAll();

        int removed = service.removeByNpcId("starter_shopkeeper");

        assertEquals(2, removed);
        assertEquals(List.of("equipment_merchant"), service.getPlacedNpcIds().stream().toList());
        verify(mobService).destroy(first.instanceId());
        verify(mobService).destroy(second.instanceId());
        verify(repository).saveAll(argThat(placements -> {
            List<NpcPlacement> saved = new java.util.ArrayList<>();
            placements.forEach(saved::add);
            return saved.size() == 1 && "equipment_merchant".equals(saved.get(0).npcId());
        }));
    }

    private PluginMock testPlugin() {
        return PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
    }
}
