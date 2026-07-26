package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillMovementServiceTest {

    @Test
    void verticalViewFallsBackToYawInsteadOfAWorldAxis() {
        Vector west = SkillMovementService.horizontal(new Vector(0.0D, 1.0D, 0.0D), 90.0F);

        assertEquals(-1.0D, west.getX(), 1.0E-9D);
        assertEquals(0.0D, west.getZ(), 1.0E-9D);
    }

    @Test
    void movementConditionPreventsTeleportSkillMovement() {
        ConditionService conditionService = mock(ConditionService.class);
        Player player = mock(Player.class);
        AstEntity mover = mock(AstEntity.class);
        World world = mock(World.class);
        Location start = new Location(world, 0.0D, 10.0D, 0.0D);
        when(player.getLocation()).thenReturn(start);
        when(player.getEyeLocation()).thenReturn(start.clone().add(0.0D, 1.62D, 0.0D));
        when(conditionService.canMove(mover)).thenReturn(false);

        SkillMovementService.MovementResult result = new SkillMovementService(conditionService)
                .blink(player, mover, 7.0D);

        assertFalse(result.moved());
        assertEquals(start, result.end());
    }

    @Test
    void sweptBodyStopsBeforeAHeadHeightObstacle() {
        ConditionService conditionService = mock(ConditionService.class);
        World world = mock(World.class);
        Block passable = mock(Block.class);
        Block solid = mock(Block.class);
        when(passable.isPassable()).thenReturn(true);
        when(solid.isPassable()).thenReturn(false);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            if (y == -1 || (y == 1 && z == 1)) {
                return solid;
            }
            return passable;
        });
        Location start = new Location(world, 0.0D, 0.0D, 0.0D);

        Location destination = new SkillMovementService(conditionService).findDestination(
                start, new Vector(0.0D, 0.0D, 1.0D), 3.0D);

        assertEquals(0.5D, destination.getZ(), 1.0E-9D);
    }
}
