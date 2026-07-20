package io.github.maaasu.astralRecord.feature.gathering.spawner.event;

import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatheringSpawnerBlockEventHandlerTest {

    @Test
    void allowsVanillaPlacementToCompleteBeforeRegisteringGatheringSpawner() {
        GatheringSpawnerService spawnerService = mock(GatheringSpawnerService.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Block placedBlock = mock(Block.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(event.getBlockPlaced()).thenReturn(placedBlock);
        when(placedBlock.getLocation()).thenReturn(location);
        when(placedBlock.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(spawnerService.readSpawnerId(any())).thenReturn("test_spawner");
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            mock(Player.class),
            event,
            EquipmentSlot.HAND,
            null,
            null,
            placedBlock,
            null,
            false,
            Objects.requireNonNull(PlayerInteractionRayTrace.create(new Vector(), new Vector(1, 0, 0), 8.0D)),
            8.0D
        );

        var candidates = new GatheringSpawnerBlockEventHandler(spawnerService).resolve(new PlayerInputContext<>(
            UUID.randomUUID(),
            0L,
            InputFamily.BLOCK_MUTATION,
            InputSource.BLOCK_PLACE,
            snapshot
        ));

        assertEquals(1, candidates.size());
        assertEquals(InputClaimPolicy.CLAIM, candidates.iterator().next().claimPolicy());
    }
}
