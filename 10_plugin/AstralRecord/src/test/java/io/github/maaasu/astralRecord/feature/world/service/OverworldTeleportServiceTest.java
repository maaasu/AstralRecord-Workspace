package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverworldTeleportServiceTest {

    @Test
    void listDestinationsReturnsOnlyOverworldsSortedByDisplayName() {
        WorldService worldService = mock(WorldService.class);
        when(worldService.getAll()).thenReturn(List.of(
                world("base", "&6Base", WorldType.BASE),
                world("greenfall", "&aGreenfall Fields", WorldType.OVERWORLD),
                world("amber", "Amber Plains", WorldType.OVERWORLD),
                world("dungeon", "Dungeon", WorldType.DUNGEON)
        ));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        List<WorldMasterData> destinations = service.listDestinations();

        assertEquals(List.of("amber", "greenfall"), destinations.stream().map(WorldMasterData::id).toList());
    }

    @Test
    void isBaseWorldUsesResolvedWorldType() {
        WorldService worldService = mock(WorldService.class);
        World baseWorld = mock(World.class);
        World overworld = mock(World.class);
        when(worldService.findByBukkitWorld(baseWorld)).thenReturn(world("base", "Base", WorldType.BASE));
        when(worldService.findByBukkitWorld(overworld)).thenReturn(world("field", "Field", WorldType.OVERWORLD));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        assertTrue(service.isBaseWorld(baseWorld));
        assertFalse(service.isBaseWorld(overworld));
    }

    private WorldMasterData world(String id, String displayName, WorldType worldType) {
        return new WorldMasterData(
                1,
                id,
                displayName,
                worldType,
                id,
                id,
                false,
                false,
                0,
                false,
                false,
                false,
                true,
                WorldSpawnLocation.defaultLocation(),
                id,
                null,
                null
        );
    }
}
