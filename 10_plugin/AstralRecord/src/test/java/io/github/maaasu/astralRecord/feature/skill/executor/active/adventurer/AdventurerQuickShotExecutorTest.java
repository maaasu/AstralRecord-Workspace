package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventurerQuickShotExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 7. 冒険者クイックショットの実装契約
     * 検証契約: クイックショットは射程12m・毎tick2.2m・半径0.45mで進み、最初のMob 1体だけへ命中する非貫通の矢として扱う。
     */
    @Test
    void usesSpecifiedNonPiercingQuickProjectile() {
        SkillProjectileSpec projectile = AdventurerQuickShotExecutor.quickShotProjectile();

        assertEquals(12.0D, projectile.range());
        assertEquals(2.2D, projectile.speed());
        assertEquals(0.45D, projectile.hitRadius());
        assertFalse(projectile.piercing());
        assertEquals(1, projectile.maxHits());
        assertEquals(SharedParticleDefinitions.SKILL_HUNTER_ARROW, projectile.trail());
        assertEquals(SharedParticleDefinitions.SKILL_HUNTER_IMPACT, projectile.impact());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 7. 冒険者クイックショットの実装契約
     * 検証契約: 発動時に指定の仮想飛翔体を1本だけ発射し、最初の命中へRANGED/NONEの300%を適用して高pitchの発射音を1回だけ鳴らす。
     */
    @Test
    void launchesQuickShotAndAppliesSpecifiedRangedDamage() {
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                mock(SkillTargetingService.class), combat, effects, projectiles,
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AdventurerQuickShotExecutor executor = new AdventurerQuickShotExecutor(services);

        SkillCastResult result = executor.cast(new SkillCastContext(
                quickShotDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eyeLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        ArgumentCaptor<SkillProjectileSpec> projectileCaptor = ArgumentCaptor.forClass(SkillProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(projectiles).launch(
                same(player), any(Location.class), any(Vector.class), projectileCaptor.capture(), hitCaptor.capture(), any()
        );
        assertEquals(AdventurerQuickShotExecutor.quickShotProjectile(), projectileCaptor.getValue());
        assertTrue(result.success());
        verify(effects, times(1)).sound(any(Location.class), eq(Sound.ENTITY_ARROW_SHOOT), eq(0.8F), eq(1.25F));

        AstEntity target = mock(AstEntity.class);
        hitCaptor.getValue().accept(target, eyeLocation);

        ArgumentCaptor<AstEntity> attackerCaptor = ArgumentCaptor.forClass(AstEntity.class);
        verify(combat).hit(
                attackerCaptor.capture(), same(target), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(3.00D)
        );
        assertSame(astPlayer, attackerCaptor.getValue().player());
    }

    private static SkillDefinition quickShotDefinition() {
        return new SkillDefinition(
                AdventurerQuickShotExecutor.ID, AdventurerQuickShotExecutor.ID, "クイックショット", null, "BOW", List.of(),
                40L, 0.0D, 0L, 1, null, Map.of(), List.of("active", "ranged", "adventurer"),
                SkillKind.ACTIVE, true, SkillResourceType.ENERGY, 6.0D
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<BiConsumer<AstEntity, Location>> biConsumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(BiConsumer.class);
    }
}
