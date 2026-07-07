package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverworldTeleportServiceDesignTest extends MockBukkitTestBase {

    @Test
    void listDestinationsKeepsOnlyOverworldsAndSortsByPlainDisplayName() {
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
    void isBaseWorldUsesWorldServiceMapping() {
        WorldService worldService = mock(WorldService.class);
        World baseWorld = mock(World.class);
        World overworld = mock(World.class);
        when(worldService.findByBukkitWorld(baseWorld)).thenReturn(world("base", "Base", WorldType.BASE));
        when(worldService.findByBukkitWorld(overworld)).thenReturn(world("field", "Field", WorldType.OVERWORLD));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        assertTrue(service.isBaseWorld(baseWorld));
        assertFalse(service.isBaseWorld(overworld));
        assertFalse(service.isBaseWorld(null));
    }

    @Test
    void teleportToDestinationRejectsMissingOrNonOverworldDestinationsBeforeTeleport() {
        WorldService worldService = mock(WorldService.class);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldMasterData base = world("base", "Base", WorldType.BASE);
        when(worldService.getById("base")).thenReturn(base);
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        service.teleportToDestination(player, astPlayer, "base");
        service.teleportToDestination(player, astPlayer, "missing");

        verify(worldService, never()).teleportToSpawnAsync(eq(player), any(WorldMasterData.class));
    }

    @Test
    void teleportToDestinationDelegatesOverworldSpawnTeleport() {
        WorldService worldService = mock(WorldService.class);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldMasterData destination = world("amber", "Amber Plains", WorldType.OVERWORLD);
        when(worldService.getById("amber")).thenReturn(destination);
        when(worldService.teleportToSpawnAsync(player, destination)).thenReturn(CompletableFuture.completedFuture(true));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        service.teleportToDestination(player, astPlayer, "amber");

        verify(worldService).teleportToSpawnAsync(player, destination);
    }

    private WorldMasterData world(String id, String displayName, WorldType worldType) {
        return new WorldMasterData(
            1,
            id,
            displayName,
            worldType,
            id,
            "world_instances",
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
