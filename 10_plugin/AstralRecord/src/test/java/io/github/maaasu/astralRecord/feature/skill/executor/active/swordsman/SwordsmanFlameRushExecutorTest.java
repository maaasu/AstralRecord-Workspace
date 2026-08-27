package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwordsmanFlameRushExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: 初撃は火属性65%だけを即時適用し、ノックバックを使わず4 tick後の縦斬りで75%を適用する。
     */
    @Test
    void firstHitHasNoKnockbackAndSecondHitFollowsAfterFourTicks() {
        Fixture fixture = fixture(7);
        ArgumentCaptor<Runnable> verticalCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.65D)
        );
        verify(fixture.combat, never()).knockback(any(AstEntity.class), any(Location.class), anyDouble(), anyDouble());
        verify(fixture.tasks).later(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanFlameRushExecutor.ID + ":vertical"),
                eq(4L),
                verticalCaptor.capture()
        );

        verticalCaptor.getValue().run();

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.75D)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: Lv.8以降の2撃目だけが、既定5秒・35%の炎上判定を伴う。
     */
    @Test
    void secondHitAddsBurningOnlyFromLevelEight() {
        Fixture fixture = fixture(8);
        ArgumentCaptor<Runnable> verticalCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<ActiveSkillCondition> conditionCaptor = ArgumentCaptor.forClass(ActiveSkillCondition.class);

        assertTrue(fixture.executor.cast(fixture.context).success());
        verify(fixture.tasks).later(any(), any(), anyLong(), verticalCaptor.capture());

        verticalCaptor.getValue().run();

        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.FIRE),
                eq(0.75D),
                conditionCaptor.capture()
        );
        ActiveSkillCondition burning = conditionCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(ConditionType.BURNING, burning.type());
        org.junit.jupiter.api.Assertions.assertEquals(35.0D, burning.chance());
        org.junit.jupiter.api.Assertions.assertEquals(100L, burning.durationTicks());
    }

    private static Fixture fixture(int skillLevel) {
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
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(world, 0.0D, 64.0D, 4.0D));
        when(targeting.inCone(same(player), eq(6.0D), eq(60.0D), eq(5), eq(true))).thenReturn(List.of(target));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.65D)))
                .thenReturn(new DamageResult(20.0D));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.75D)))
                .thenReturn(new DamageResult(20.0D));
        when(combat.hit(
                any(AstEntity.class),
                same(target),
                eq(AttackType.MELEE),
                eq(DamageElement.FIRE),
                eq(0.75D),
                any(ActiveSkillCondition.class)
        )).thenReturn(new DamageResult(20.0D));
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        LearnedSkillInstance learned = new LearnedSkillInstance(
                UUID.randomUUID(), UUID.randomUUID(), SwordsmanFlameRushExecutor.ID, skillLevel, List.of(), 0, null, null
        );
        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                player.getEyeLocation(),
                snapshot,
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH,
                learned
        );
        return new Fixture(player, target, combat, tasks, new SwordsmanFlameRushExecutor(services), context);
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                SwordsmanFlameRushExecutor.ID,
                SwordsmanFlameRushExecutor.ID,
                "フレイムラッシュ",
                null,
                "BLAZE_POWDER",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "range", 6.0D,
                        "targetAngle", 60.0D,
                        "maxTargets", 5,
                        "damageRatios", List.of(0.65D, 0.75D),
                        "secondHitDelayTicks", 4,
                        "burningUnlockLevel", 8,
                        "burningChance", 35.0D,
                        "burningDurationTicks", 100L
                ),
                List.of("active", "melee", "fire"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D,
                null,
                10
        );
    }

    private record Fixture(
            Player player,
            AstEntity target,
            SkillCombatService combat,
            SkillTaskService tasks,
            SwordsmanFlameRushExecutor executor,
            SkillCastContext context
    ) {
    }
}
