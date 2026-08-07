package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillTargetingServiceTest {

    private static final BoundingBox STANDARD_MOB = new BoundingBox(
            -0.3D, 0.0D, 4.7D,
            0.3D, 1.8D, 5.3D
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 水平eye rayとMob実body bounding boxの交差をhitとする。
     */
    @Test
    void horizontalEyeRayHitsTheMobsActualBodyBounds() {
        assertTrue(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.62D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line capsule半径で拡張したbounds外のtargetをhitにしない。
     */
    @Test
    void capsuleRadiusDoesNotHitTargetsOutsideItsExpandedBounds() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 2.4D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line originより後方のtargetをhitにしない。
     */
    @Test
    void lineDoesNotHitTargetsBehindItsOrigin() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.0D, 10.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.5D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: target center距離でなくrayがbody前面へ入る交差距離でhit順を決める。
     */
    @Test
    void intersectionDistanceOrdersByFrontFaceInsteadOfTargetCenter() {
        BoundingBox largeFrontTarget = new BoundingBox(-1.0D, 0.0D, 2.0D, 1.0D, 3.0D, 10.0D);
        BoundingBox smallRearTarget = new BoundingBox(-0.3D, 0.0D, 4.0D, 0.3D, 1.8D, 4.5D);
        Vector origin = new Vector(0.0D, 1.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        double largeDistance = SkillTargetingService.lineIntersectionDistance(
                largeFrontTarget, origin, direction, 20.0D, 0.0D
        );
        double smallDistance = SkillTargetingService.lineIntersectionDistance(
                smallRearTarget, origin, direction, 20.0D, 0.0D
        );

        assertEquals(2.0D, largeDistance, 1.0E-9D);
        assertEquals(4.0D, smallDistance, 1.0E-9D);
        assertTrue(largeDistance < smallDistance);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: Block面の直前で capsule がMobへ交差した場合だけ命中とし、Block面と同距離の交差はBlockを優先する。
     */
    @Test
    void lineBeforeBlockIncludesOnlyMobCollisionsStrictlyBeforeBlockImpact() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobInstance mob = mock(MobInstance.class);
        MobTemplate template = mock(MobTemplate.class);
        when(player.getWorld()).thenReturn(world);
        when(mobService.getInstances()).thenReturn(List.of(mob));
        when(mob.state()).thenReturn(MobState.IDLE);
        when(mob.template()).thenReturn(template);
        when(template.category()).thenReturn(MobCategory.ENEMY);
        when(mob.instanceId()).thenReturn(UUID.randomUUID());
        when(mob.bukkitEntityId()).thenReturn(null);

        SkillTargetingService service = new SkillTargetingService(mobService);
        Location origin = new Location(world, 0.0D, 1.0D, 0.0D);
        when(mob.currentLocation()).thenReturn(new Location(world, 1.349D, 0.0D, 0.0D));

        List<AstEntity> beforeBlock = service.inLineBeforeBlock(
                player, origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.0D, 1
        );
        when(mob.currentLocation()).thenReturn(new Location(world, 1.35D, 0.0D, 0.0D));
        List<AstEntity> atBlock = service.inLineBeforeBlock(
                player, origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.0D, 1
        );

        assertEquals(1, beforeBlock.size());
        assertTrue(atBlock.isEmpty());
    }
}
