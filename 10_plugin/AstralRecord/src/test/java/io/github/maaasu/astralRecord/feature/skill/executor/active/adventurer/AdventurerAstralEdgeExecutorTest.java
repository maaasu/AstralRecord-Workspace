package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventurerAstralEdgeExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック > ### 6.1 アストラルエッジ変更契約
     * 検証契約: 薙ぎ払いと突き刺しの各有効命中が独立して最大ENG割合の回復を1回ずつ発生させる。
     */
    @Test
    void recoversEnergyForEachIndependentHit() {
        Fixture fixture = fixture(new DamageResult(10.0D), new DamageResult(10.0D));
        ArgumentCaptor<IntConsumer> sweepCaptor = ArgumentCaptor.forClass(IntConsumer.class);
        ArgumentCaptor<Runnable> thrustCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                eq(AdventurerAstralEdgeExecutor.ID + ":sweep"),
                eq(0L),
                eq(1L),
                eq(6),
                sweepCaptor.capture()
        );
        sweepCaptor.getValue().accept(0);

        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.NONE),
                eq(1.2D)
        );
        verify(fixture.combat).recoverEnergyByMaxRatio(same(fixture.astPlayer), eq(0.05D));
        verify(fixture.tasks).later(
                eq(fixture.player.getUniqueId()),
                eq(AdventurerAstralEdgeExecutor.ID + ":thrust:" + fixture.targetId),
                eq(4L),
                thrustCaptor.capture()
        );

        thrustCaptor.getValue().run();

        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.NONE),
                eq(0.6D)
        );
        verify(fixture.combat, times(2)).recoverEnergyByMaxRatio(same(fixture.astPlayer), eq(0.05D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック > ### 6.1 アストラルエッジ変更契約
     * 検証契約: 薙ぎ払いが回避されても、突き刺しは4 tick後に独立して実行され、突き刺しの命中だけでENGを回復する。
     */
    @Test
    void executesSecondHitAfterFirstHitEvades() {
        Fixture fixture = fixture(DamageResult.evaded(0.0D, 100.0D, 100.0D), new DamageResult(10.0D));
        ArgumentCaptor<IntConsumer> sweepCaptor = ArgumentCaptor.forClass(IntConsumer.class);
        ArgumentCaptor<Runnable> thrustCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertTrue(fixture.executor.cast(fixture.context).success());
        verify(fixture.tasks).repeat(any(UUID.class), eq(AdventurerAstralEdgeExecutor.ID + ":sweep"),
                eq(0L), eq(1L), eq(6), sweepCaptor.capture());

        sweepCaptor.getValue().accept(0);

        verify(fixture.combat, never()).recoverEnergyByMaxRatio(any(AstPlayer.class), anyDouble());
        verify(fixture.tasks).later(any(UUID.class), eq(AdventurerAstralEdgeExecutor.ID + ":thrust:" + fixture.targetId),
                eq(4L), thrustCaptor.capture());

        thrustCaptor.getValue().run();

        verify(fixture.combat).recoverEnergyByMaxRatio(same(fixture.astPlayer), eq(0.05D));
    }

    private static Fixture fixture(DamageResult sweepResult, DamageResult thrustResult) {
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
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        Location eyeLocation = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(eyeLocation);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        UUID targetId = UUID.randomUUID();
        when(target.id()).thenReturn(targetId);
        when(target.location()).thenReturn(new Location(world, 0.0D, 64.0D, 3.0D));
        when(targeting.inViewArcSegment(
                same(player), any(Location.class), any(org.bukkit.util.Vector.class),
                anyDouble(), anyDouble(), anyDouble(), anyInt(), anyBoolean()
        )).thenReturn(List.of(target));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(1.2D)))
                .thenReturn(sweepResult);
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(0.6D)))
                .thenReturn(thrustResult);

        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eyeLocation,
                io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot.empty(),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(
                player,
                astPlayer,
                target,
                targetId,
                combat,
                tasks,
                new AdventurerAstralEdgeExecutor(services),
                context
        );
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                AdventurerAstralEdgeExecutor.ID,
                AdventurerAstralEdgeExecutor.ID,
                "アストラルエッジ",
                null,
                "IRON_SWORD",
                List.of(),
                50L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "reach", 5.5D,
                        "maxTargets", 5,
                        "damageRatios", List.of(1.2D, 0.6D),
                        "energyRecoveryRatio", 0.05D
                ),
                List.of("active", "melee", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                8.0D
        );
    }

    private record Fixture(
            Player player,
            AstPlayer astPlayer,
            AstEntity target,
            UUID targetId,
            SkillCombatService combat,
            SkillTaskService tasks,
            AdventurerAstralEdgeExecutor executor,
            SkillCastContext context
    ) {
    }
}
