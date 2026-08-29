package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillRegistry;
import io.github.maaasu.astralRecord.feature.mob.skill.skeletonarcher.SkeletonArcherBowShotMobSkillExecutor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### AI スキル攻撃
     * 検証契約: 上下照準を許可するスケルトン・アーチャーの弓矢は、三次元距離が発動距離内なら高低差が1.25 blockを超えても発動距離内と判定する。
     */
    @Test
    void skeletonArcherBowShotUsesThreeDimensionalActivationRange() {
        World world = mock(World.class);
        MobInstance instance = mock(MobInstance.class);
        Player target = mock(Player.class);
        when(instance.currentLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(target.getLocation()).thenReturn(new Location(world, 10.0D, 68.0D, 0.0D));

        MobSkillRegistry registry = new MobSkillRegistry();
        registry.register(new SkeletonArcherBowShotMobSkillExecutor(mock(DamageService.class), mock(MobProjectileService.class)));
        MobSkillService service = new MobSkillService(mock(MobService.class), registry);
        MobSkillBinding binding = new MobSkillBinding(
                SkeletonArcherBowShotMobSkillExecutor.SKILL_ID,
                16.0D,
                null,
                null,
                Map.of()
        );

        assertTrue(service.isWithinActivationRange(instance, binding, target, 12.25D));

        when(target.getLocation()).thenReturn(new Location(world, 10.0D, 78.0D, 0.0D));
        assertFalse(service.isWithinActivationRange(instance, binding, target, 12.25D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### AI スキル攻撃
     * 検証契約: 上下照準を許可しないスキルは、同じ高さでは水平射程で判定し、高低差が1.25 blockを超える対象を発動距離外と判定する。
     */
    @Test
    void groundSkillRetainsVerticalTolerance() {
        World world = mock(World.class);
        MobInstance instance = mock(MobInstance.class);
        Player target = mock(Player.class);
        when(instance.currentLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(target.getLocation()).thenReturn(new Location(world, 10.0D, 64.0D, 0.0D));

        MobSkillRegistry registry = new MobSkillRegistry();
        registry.register(groundSkillExecutor());
        MobSkillService service = new MobSkillService(mock(MobService.class), registry);
        MobSkillBinding binding = new MobSkillBinding("mob_ground_test", 16.0D, null, null, Map.of());

        assertTrue(service.isWithinActivationRange(instance, binding, target, 12.25D));

        when(target.getLocation()).thenReturn(new Location(world, 10.0D, 66.0D, 0.0D));
        assertFalse(service.isWithinActivationRange(instance, binding, target, 12.25D));
    }

    private static MobSkillExecutor groundSkillExecutor() {
        return new MobSkillExecutor() {
            @Override
            public String id() {
                return "mob_ground_test";
            }

            @Override
            public String displayName() {
                return "地上テスト";
            }

            @Override
            public MobSkillTiming defaultTiming() {
                return new MobSkillTiming(16.0D, 20L, 0L);
            }

            @Override
            public boolean cast(MobSkillContext context) {
                return true;
            }
        };
    }
}
