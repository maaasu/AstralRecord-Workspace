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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 16. ハンターフェイドショットの実装契約
     * 検証契約: velocity後退はテレポートせず、視線と反対方向の水平velocityを設定する。
     */
    @Test
    void appliesHorizontalBackstepVelocityWithoutTeleport() {
        ConditionService conditionService = mock(ConditionService.class);
        Player player = mock(Player.class);
        AstEntity mover = mock(AstEntity.class);
        Location playerLocation = new Location(null, 0.0D, 10.0D, 0.0D, 0.0F, 0.0F);
        Location eye = new Location(null, 0.0D, 11.62D, 0.0D, 0.0F, 0.0F);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getEyeLocation()).thenReturn(eye);
        when(conditionService.canMove(mover)).thenReturn(true);

        Vector velocity = new SkillMovementService(conditionService)
                .backstepVelocity(player, mover, 0.35D);

        assertEquals(new Vector(0.0D, 0.0D, -0.35D), velocity);
        verify(player).setVelocity(eq(new Vector(0.0D, 0.0D, -0.35D)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 28. ソードマン エクゼプトスタンプの実装契約 > ### 28.2 演出・実装境界
     * 検証契約: 汎用velocityは移動禁止中に設定せず、nullを返す。
     */
    @Test
    void genericVelocityDoesNotApplyWhenMovementIsBlocked() {
        ConditionService conditionService = mock(ConditionService.class);
        Player player = mock(Player.class);
        AstEntity mover = mock(AstEntity.class);
        when(conditionService.canMove(mover)).thenReturn(false);

        Vector velocity = new SkillMovementService(conditionService).velocity(
                player,
                mover,
                new Vector(0.0D, 1.0D, 0.0D)
        );

        assertNull(velocity);
        verify(player, never()).setVelocity(org.mockito.ArgumentMatchers.any(Vector.class));
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
