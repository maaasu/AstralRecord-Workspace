package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventurerLightningBoltExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 8. 冒険者ライトニングボルトの実装契約
     * 検証契約: ライトニングボルトは射程14m・速度2.8m/tick・半径0.45mの非貫通飛翔体を1体命中で終了する。
     */
    @Test
    void usesSpecifiedLightningProjectile() {
        SkillProjectileSpec projectile = AdventurerLightningBoltExecutor.lightningBoltProjectile();

        assertEquals(14.0D, projectile.range());
        assertEquals(2.8D, projectile.speed());
        assertEquals(0.45D, projectile.hitRadius());
        assertFalse(projectile.piercing());
        assertEquals(1, projectile.maxHits());
        assertEquals(SharedParticleDefinitions.SKILL_MAGE_LIGHTNING, projectile.trail());
        assertEquals(SharedParticleDefinitions.SKILL_MAGE_LIGHTNING, projectile.impact());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 8. 冒険者ライトニングボルトの実装契約
     * 検証契約: 主対象へ雷属性217.5%を適用し、非感電対象を飛ばして近い感電対象だけ最大2体へ60%を1段連鎖する。主対象の状態確認、再連鎖、感電付与は行わない。
     */
    @Test
    void appliesPrimaryAndOnlyShockedChainTargets() {
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, combat, effects, projectiles,
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);

        AstEntity primary = target(UUID.fromString("00000000-0000-0000-0000-000000000001"), 7.0D, 64.0D, 3.0D);
        AstEntity nonShocked = target(UUID.fromString("00000000-0000-0000-0000-000000000002"), 8.0D, 64.0D, 3.0D);
        AstEntity shockedNear = target(UUID.fromString("00000000-0000-0000-0000-000000000003"), 8.5D, 64.0D, 3.0D);
        AstEntity shockedFar = target(UUID.fromString("00000000-0000-0000-0000-000000000004"), 9.0D, 64.0D, 3.0D);
        AstEntity shockedOverLimit = target(UUID.fromString("00000000-0000-0000-0000-000000000005"), 9.5D, 64.0D, 3.0D);
        when(combat.hasCondition(nonShocked, ConditionType.SHOCKED)).thenReturn(false);
        when(combat.hasCondition(shockedNear, ConditionType.SHOCKED)).thenReturn(true);
        when(combat.hasCondition(shockedFar, ConditionType.SHOCKED)).thenReturn(true);
        when(combat.hasCondition(shockedOverLimit, ConditionType.SHOCKED)).thenReturn(true);
        Location primaryImpact = new Location(null, 7.0D, 65.0D, 3.0D);
        when(targeting.inRadius(
                same(player), any(Location.class), eq(5.0D), eq(5.0D), eq(Integer.MAX_VALUE), eq(true)
        )).thenReturn(List.of(primary, nonShocked, shockedNear, shockedFar, shockedOverLimit));

        AdventurerLightningBoltExecutor executor = new AdventurerLightningBoltExecutor(services);
        SkillCastResult result = executor.cast(new SkillCastContext(
                lightningBoltDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eyeLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        ArgumentCaptor<SkillProjectileSpec> projectileCaptor = ArgumentCaptor.forClass(SkillProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(projectiles).launch(
                same(player), any(Location.class), any(Vector.class), projectileCaptor.capture(), hitCaptor.capture(), any()
        );
        assertEquals(AdventurerLightningBoltExecutor.lightningBoltProjectile(), projectileCaptor.getValue());
        assertTrue(result.success());
        verify(effects).point(any(Location.class), eq(SharedParticleDefinitions.SKILL_MAGE_LIGHTNING));

        hitCaptor.getValue().accept(primary, primaryImpact);

        verify(combat).hit(any(AstEntity.class), same(primary), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), eq(2.175D));
        verify(combat).hit(any(AstEntity.class), same(shockedNear), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), eq(0.60D));
        verify(combat).hit(any(AstEntity.class), same(shockedFar), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), eq(0.60D));
        verify(combat, never()).hit(any(AstEntity.class), same(nonShocked), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), eq(0.60D));
        verify(combat, never()).hit(any(AstEntity.class), same(shockedOverLimit), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), eq(0.60D));
        ArgumentCaptor<AstEntity> attackerCaptor = ArgumentCaptor.forClass(AstEntity.class);
        verify(combat, times(3)).hit(
                attackerCaptor.capture(), any(AstEntity.class), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING), anyDouble()
        );
        assertTrue(attackerCaptor.getAllValues().stream().allMatch(attacker -> attacker.player() == astPlayer));
        verify(combat, never()).hasCondition(same(primary), eq(ConditionType.SHOCKED));
        verify(effects, times(2)).line(
                same(primaryImpact), any(Location.class), eq(0.22D), eq(SharedParticleDefinitions.CONDITION_SHOCKED_SPARK)
        );
    }

    private static AstEntity target(UUID id, double x, double y, double z) {
        AstEntity target = mock(AstEntity.class);
        when(target.id()).thenReturn(id);
        when(target.location()).thenReturn(new Location(null, x, y, z));
        return target;
    }

    private static SkillDefinition lightningBoltDefinition() {
        return new SkillDefinition(
                AdventurerLightningBoltExecutor.ID,
                AdventurerLightningBoltExecutor.ID,
                "ライトニングボルト",
                null,
                "LIGHTNING_ROD",
                List.of(),
                60L,
                0.0D,
                4L,
                1,
                null,
                Map.of(
                        "range", 14.0D,
                        "damageRatio", 2.175D,
                        "chainRadius", 5.0D,
                        "chainDamageRatio", 0.60D,
                        "maxChainTargets", 2,
                        "projectileSpeed", 2.8D,
                        "projectileHitRadius", 0.45D
                ),
                List.of("active", "magic", "adventurer", "lightning"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                10.0D
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<BiConsumer<AstEntity, Location>> biConsumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(BiConsumer.class);
    }
}
