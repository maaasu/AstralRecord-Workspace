package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldSpawnParticleTaskTest extends MockBukkitTestBase {

    @Test
    void tickSkipsWorldsWithSpawnParticleDisabled() throws Exception {
        WorldService worldService = mock(WorldService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        AstralRecord plugin = mock(AstralRecord.class);

        WorldMasterData visibleWorld = new WorldMasterData(
                1,
                "visible",
                "visible",
                WorldType.BASE,
                "base",
                "instance",
                false,
                false,
                0,
                false,
                false,
                false,
                true,
                WorldSpawnLocation.defaultLocation(),
                "visible"
        );
        WorldMasterData hiddenWorld = new WorldMasterData(
                1,
                "hidden",
                "hidden",
                WorldType.HUB,
                "base",
                "instance",
                false,
                false,
                0,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "hidden"
        );

        var visibleBukkitWorld = server().addSimpleWorld("visible-world");
        Location visibleSpawn = new Location(visibleBukkitWorld, 0.5D, 64.0D, 0.5D);
        Location hiddenSpawn = new Location(server().addSimpleWorld("hidden-world"), 10.5D, 70.0D, 10.5D);
        server().addPlayer().teleport(visibleSpawn);
        when(worldService.getAll()).thenReturn(List.of(visibleWorld, hiddenWorld));
        when(worldService.resolveSpawnLocation(visibleWorld)).thenReturn(visibleSpawn);
        when(worldService.resolveSpawnLocation(hiddenWorld)).thenReturn(hiddenSpawn);

        WorldSpawnParticleTask task = new WorldSpawnParticleTask(plugin, worldService, particleDisplayService);
        Method tick = WorldSpawnParticleTask.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(task);

        verify(worldService).resolveSpawnLocation(eq(visibleWorld));
        verify(worldService, never()).resolveSpawnLocation(eq(hiddenWorld));
        verify(particleDisplayService, times(1)).spawnForNearbyViewers(
                eq(visibleSpawn),
                argThat(locations -> locations.size() == 6),
                eq(SharedParticleDefinitions.WORLD_SPAWN_RING_END_ROD)
        );
    }
}
