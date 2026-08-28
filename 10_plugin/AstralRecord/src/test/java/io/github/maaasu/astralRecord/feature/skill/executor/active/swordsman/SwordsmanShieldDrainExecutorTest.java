package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwordsmanShieldDrainExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: Shield非最大で実Shieldを40削ったhitは基礎65%・Shield3倍を使い、その50%に当たる20だけ自身へ回復要求する。
     */
    @Test
    void drainsHalfOfActualShieldDamageWhenNotFullAtCast() {
        Fixture fixture = fixture(50.0D, 100.0D, DamageResult.shield(40.0D, false));
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(20.0D))).thenReturn(20.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(0.65D), eq(3.0D)
        );
        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(20.0D));
        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanShieldDrainExecutor.ID + ":absorb"),
                eq(1L),
                eq(1L),
                eq(4),
                any(IntConsumer.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: 吸収粒子は対象から使用者へ4frameで単調に補間し、到達frameだけで円形Shieldと到達音を表示する。
     */
    @Test
    void absorptionFramesTravelFromTargetAndShowArrivalEffectsOnlyAtLastFrame() {
        Fixture fixture = fixture(50.0D, 100.0D, DamageResult.shield(40.0D, false));
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(20.0D))).thenReturn(20.0D);
        ArgumentCaptor<IntConsumer> frameCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor.cast(fixture.context).success());
        verify(fixture.tasks).repeat(any(), any(), anyLong(), anyLong(), anyInt(), frameCaptor.capture());
        clearInvocations(fixture.effects);

        IntConsumer frames = frameCaptor.getValue();
        frames.accept(0);
        frames.accept(1);
        frames.accept(2);

        verify(fixture.effects, times(3)).point(any(Location.class), eq(SharedParticleDefinitions.SHIELD_DRAIN_ABSORB_END_ROD));
        verify(fixture.effects, never()).ring(any(Location.class), anyDouble(), anyInt(), any());
        verify(fixture.effects, never()).sound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());

        frames.accept(3);

        ArgumentCaptor<Location> pointCaptor = ArgumentCaptor.forClass(Location.class);
        verify(fixture.effects, times(4)).point(pointCaptor.capture(), eq(SharedParticleDefinitions.SHIELD_DRAIN_ABSORB_END_ROD));
        List<Location> points = pointCaptor.getAllValues();
        double[] expectedZ = {3.0D, 2.0D, 1.0D, 0.0D};
        double[] expectedY = {64.925D, 64.95D, 64.975D, 65.0D};
        for (int frame = 0; frame < points.size(); frame++) {
            assertEquals(expectedZ[frame], points.get(frame).getZ(), 0.0001D);
            assertEquals(expectedY[frame], points.get(frame).getY(), 0.0001D);
        }
        verify(fixture.effects).ring(any(Location.class), eq(0.85D), eq(18), eq(SharedParticleDefinitions.SHIELD_DRAIN_RING_DUST));
        verify(fixture.effects).sound(any(Location.class), eq(Sound.ITEM_ARMOR_EQUIP_DIAMOND), eq(0.8F), eq(1.45F));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 4. 個別 executor の規約
     * 検証契約: 吸収タスク実行時に使用者がofflineまたは対象と別worldなら粒子・到達リング・到達音を表示しない。
     */
    @Test
    void absorptionFrameSkipsEffectsForOfflineOrDifferentWorldCaster() {
        Fixture fixture = fixture(50.0D, 100.0D, DamageResult.shield(40.0D, false));
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(20.0D))).thenReturn(20.0D);
        ArgumentCaptor<IntConsumer> frameCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor.cast(fixture.context).success());
        verify(fixture.tasks).repeat(any(), any(), anyLong(), anyLong(), anyInt(), frameCaptor.capture());
        clearInvocations(fixture.effects);

        when(fixture.player.isOnline()).thenReturn(false);
        frameCaptor.getValue().accept(0);
        when(fixture.player.isOnline()).thenReturn(true);
        when(fixture.player.getWorld()).thenReturn(mock(World.class));
        frameCaptor.getValue().accept(3);

        verify(fixture.effects, never()).point(any(Location.class), any());
        verify(fixture.effects, never()).ring(any(Location.class), anyDouble(), anyInt(), any());
        verify(fixture.effects, never()).sound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: 発動時Shieldが最大でも基礎65%を維持し、実回復量0なら吸収到達タスクを使わない。
     */
    @Test
    void keepsBaseDamageAndSkipsAbsorptionWhenNoShieldIsRecovered() {
        Fixture fixture = fixture(100.0D, 100.0D, DamageResult.shield(40.0D, false));

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(0.65D), eq(3.0D)
        );
        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(20.0D));
        verify(fixture.tasks, never()).repeat(any(), any(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: 対象Shieldを実際に削れなかったhitは予定ダメージに関係なく自身のShieldを回復しない。
     */
    @Test
    void doesNotRecoverWhenHitDealsNoShieldDamage() {
        Fixture fixture = fixture(25.0D, 100.0D, new DamageResult(30.0D));

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat, never()).recoverShield(any(AstEntity.class), anyDouble());
    }

    private static Fixture fixture(double currentShield, double maxShield, DamageResult damageResult) {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F));
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(world, 0.0D, 64.0D, 4.0D));
        when(targeting.inCone(same(player), eq(6.0D), eq(40.0D), eq(1), eq(true))).thenReturn(List.of(target));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.NONE), anyDouble(), eq(3.0D)))
                .thenReturn(damageResult);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(snapshot.getCurrentShield()).thenReturn(currentShield);
        when(snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(maxShield);
        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                player.getEyeLocation(),
                snapshot,
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(
                player,
                target,
                combat,
                effects,
                tasks,
                new SwordsmanShieldDrainExecutor(services),
                context
        );
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                SwordsmanShieldDrainExecutor.ID,
                SwordsmanShieldDrainExecutor.ID,
                "シールドドレイン",
                null,
                "TUBE_CORAL",
                List.of(),
                60L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "range", 6.0D,
                        "targetAngle", 40.0D,
                        "damageRatio", 0.65D,
                        "shieldBreakMultiplier", 3.0D,
                        "shieldAbsorbRatio", 0.50D
                ),
                List.of("active", "melee"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                10.0D
        );
    }

    private record Fixture(
            Player player,
            AstEntity target,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillTaskService tasks,
            SwordsmanShieldDrainExecutor executor,
            SkillCastContext context
    ) {
    }
}
