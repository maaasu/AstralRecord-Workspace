package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseWorldSpawnTeleportEventHandlerTest extends MockBukkitTestBase {

    @Test
    void opensOverworldTeleportGuiWhenSneakingInsideBaseSpawnTrigger() {
        WorldService worldService = mock(WorldService.class);
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        OverworldTeleportGuiEventHandler guiEventHandler = mock(OverworldTeleportGuiEventHandler.class);
        BaseWorldSpawnTeleportEventHandler handler =
                new BaseWorldSpawnTeleportEventHandler(worldService, teleportService, guiEventHandler);

        World world = server().addSimpleWorld("base");
        Player player = server().addPlayer();
        player.teleport(new Location(world, 101.0D, 64.0D, 200.0D));
        WorldMasterData baseWorld = world("base", WorldType.BASE);
        Location spawn = new Location(world, 100.0D, 64.0D, 200.0D);
        when(teleportService.isBaseWorld(world)).thenReturn(true);
        when(worldService.findByBukkitWorld(world)).thenReturn(baseWorld);
        when(worldService.resolveSpawnLocation(baseWorld)).thenReturn(spawn);

        handler.onPlayerToggleSneak(new PlayerToggleSneakEvent(player, true));

        verify(guiEventHandler).open(player);
    }

    @Test
    void ignoresSneakOutsideBaseSpawnTrigger() {
        WorldService worldService = mock(WorldService.class);
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        OverworldTeleportGuiEventHandler guiEventHandler = mock(OverworldTeleportGuiEventHandler.class);
        BaseWorldSpawnTeleportEventHandler handler =
                new BaseWorldSpawnTeleportEventHandler(worldService, teleportService, guiEventHandler);

        World world = server().addSimpleWorld("base");
        Player player = server().addPlayer();
        player.teleport(new Location(world, 103.0D, 64.0D, 200.0D));
        WorldMasterData baseWorld = world("base", WorldType.BASE);
        Location spawn = new Location(world, 100.0D, 64.0D, 200.0D);
        when(teleportService.isBaseWorld(world)).thenReturn(true);
        when(worldService.findByBukkitWorld(world)).thenReturn(baseWorld);
        when(worldService.resolveSpawnLocation(baseWorld)).thenReturn(spawn);

        handler.onPlayerToggleSneak(new PlayerToggleSneakEvent(player, true));

        verify(guiEventHandler, never()).open(player);
    }

    @Test
    void ignoresSneakOutsideBaseWorld() {
        WorldService worldService = mock(WorldService.class);
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        OverworldTeleportGuiEventHandler guiEventHandler = mock(OverworldTeleportGuiEventHandler.class);
        BaseWorldSpawnTeleportEventHandler handler =
                new BaseWorldSpawnTeleportEventHandler(worldService, teleportService, guiEventHandler);

        World world = server().addSimpleWorld("field");
        Player player = server().addPlayer();
        player.teleport(new Location(world, 100.0D, 64.0D, 200.0D));
        when(teleportService.isBaseWorld(world)).thenReturn(false);

        handler.onPlayerToggleSneak(new PlayerToggleSneakEvent(player, true));

        verify(guiEventHandler, never()).open(player);
    }

    private WorldMasterData world(String id, WorldType worldType) {
        return new WorldMasterData(
                1,
                id,
                id,
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
