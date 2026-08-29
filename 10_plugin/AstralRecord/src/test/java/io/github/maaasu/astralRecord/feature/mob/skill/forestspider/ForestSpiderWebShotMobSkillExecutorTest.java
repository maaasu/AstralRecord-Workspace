package io.github.maaasu.astralRecord.feature.mob.skill.forestspider;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForestSpiderWebShotMobSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: クモ糸スキルの executor は宣言済みの数値パラメーターだけを受け付け、未知のキーを拒否する。
     */
    @Test
    void acceptsOnlyDeclaredWebParameters() {
        ForestSpiderWebShotMobSkillExecutor executor = executor();

        assertDoesNotThrow(() -> executor.validate(new MobSkillBinding(
                ForestSpiderWebShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of(
                        "damageRatio", 0.75D,
                        "projectileSpeed", 0.90D,
                        "projectileHitRadius", 0.25D,
                        "weaknessChance", 25.0D,
                        "weaknessDurationTicks", 100.0D
                )
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                ForestSpiderWebShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("unexpected", 1.0D)
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 5. フォレストスパイダーのクモ糸
     * 検証契約: クモ糸スキルは既定で近距離の9m、40 tick cooldown、10 tick詠唱を使い、高低差のある対象を照準できる。
     */
    @Test
    void usesCloseThreeDimensionalTargetingDefaults() {
        ForestSpiderWebShotMobSkillExecutor executor = executor();
        MobSkillTiming timing = executor.defaultTiming();

        assertEquals(9.0D, timing.activationRange());
        assertEquals(40L, timing.cooldownTicks());
        assertEquals(10L, timing.castTimeTicks());
        assertTrue(executor.allowsVerticalTargeting());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 5. フォレストスパイダーのクモ糸
     * 検証契約: executor 起点のクモ糸は tick 飛翔体としてプレイヤー hitbox に命中し、RANGED ダメージ後に有効な衰弱付与要求を発行する。
     */
    @Test
    void launchesWebAndAppliesWeaknessAfterSuccessfulHit() {
        PluginMock plugin = MockBukkit.createMockPlugin("ForestSpiderWebShotMobSkillExecutorTest");
        MobService mobService = mock(MobService.class);
        MobInstance caster = mock(MobInstance.class);
        UUID casterId = UUID.randomUUID();
        when(caster.instanceId()).thenReturn(casterId);
        when(mobService.plugin()).thenReturn(plugin);
        when(mobService.getInstance(casterId)).thenReturn(caster);

        World world = mock(World.class);
        Player player = mock(Player.class);
        when(world.getPlayers()).thenReturn(java.util.List.of(player));
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble())).thenReturn(null);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(
                0.75D, -0.5D, -0.5D,
                1.25D, 1.5D, 0.5D
        ));

        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        when(damageService.resolveEntity(player)).thenReturn(target);
        when(damageService.attack(
                any(AstEntity.class), eq(target), eq(AttackType.RANGED), anyList(), eq(DamageSource.SKILL)
        )).thenReturn(new DamageResult(10.0D));
        ConditionService conditionService = mock(ConditionService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        MobProjectileService projectileService = new MobProjectileService(mobService, particleDisplayService);
        ForestSpiderWebShotMobSkillExecutor executor = new ForestSpiderWebShotMobSkillExecutor(
                damageService, conditionService, projectileService
        );
        MobSkillBinding binding = new MobSkillBinding(
                ForestSpiderWebShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of(
                        "damageRatio", 0.75D,
                        "projectileSpeed", 1.0D,
                        "projectileHitRadius", 0.0D,
                        "weaknessChance", 25.0D,
                        "weaknessDurationTicks", 100.0D
                )
        );
        Location origin = new Location(world, 0.0D, 0.0D, 0.0D);

        assertTrue(executor.cast(new MobSkillContext(
                caster, player, binding, executor.defaultTiming(), origin, new Vector(1.0D, 0.0D, 0.0D)
        )));
        server().getScheduler().performTicks(2L);

        verify(damageService).attack(
                any(AstEntity.class), eq(target), eq(AttackType.RANGED), anyList(), eq(DamageSource.SKILL)
        );
        ArgumentCaptor<ConditionApplyRequest> request = ArgumentCaptor.forClass(ConditionApplyRequest.class);
        verify(conditionService).applyCondition(request.capture());
        assertEquals(target, request.getValue().target());
        assertEquals(ConditionType.WEAKNESS, request.getValue().type());
        assertEquals(AttackType.RANGED, request.getValue().attackType());
        assertEquals(100L, request.getValue().durationTicks());
        assertEquals(25.0D, request.getValue().chance());
        projectileService.stop();
    }

    private static ForestSpiderWebShotMobSkillExecutor executor() {
        return new ForestSpiderWebShotMobSkillExecutor(
                mock(DamageService.class),
                mock(ConditionService.class),
                mock(MobProjectileService.class)
        );
    }
}
