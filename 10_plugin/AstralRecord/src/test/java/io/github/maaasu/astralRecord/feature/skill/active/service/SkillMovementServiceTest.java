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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 視線が垂直でもworld固定軸でなくplayer yawから水平移動方向を決める。
     */
    @Test
    void verticalViewFallsBackToYawInsteadOfAWorldAxis() {
        Vector west = SkillMovementService.horizontal(new Vector(0.0D, 1.0D, 0.0D), 90.0F);

        assertEquals(-1.0D, west.getX(), 1.0E-9D);
        assertEquals(0.0D, west.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 移動禁止condition中はdash/backstep/blinkのteleport移動を行わない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: player bodyをsweepして頭高の障害物手前でskill移動を停止する。
     */
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
