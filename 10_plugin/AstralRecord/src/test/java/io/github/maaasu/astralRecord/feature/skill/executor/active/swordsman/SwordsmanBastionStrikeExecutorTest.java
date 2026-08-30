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
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwordsmanBastionStrikeExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 発動時に現在MP45を使い切り、不足Shield75を即時回復してから前方対象へ250%の近接hitを適用する。
     */
    @Test
    void consumesAllCurrentManaAndRestoresOnlyMissingShieldBeforeTargetHit() {
        Fixture fixture = fixture(new DamageResult(30.0D, false), 1, 100.0D);
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(75.0D))).thenReturn(75.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(2.50D)
        );
        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(75.0D));
        verify(fixture.astPlayer).setStatusSnapshot(same(fixture.afterManaSnapshot));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 回避または有効ダメージ0のhitでも、発動時に現在MPを消費して不足Shieldを即時回復する。
     */
    @Test
    void restoresShieldEvenWhenTargetHitDoesNotLand() {
        Fixture fixture = fixture(DamageResult.evaded(0.0D, 0.0D, 0.0D), 1, 100.0D);
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(75.0D))).thenReturn(75.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(75.0D));
        verify(fixture.astPlayer).setStatusSnapshot(same(fixture.afterManaSnapshot));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 前方に対象がいなくても、発動時に現在MPを消費して不足Shieldを即時回復する。
     */
    @Test
    void restoresShieldWhenNoTargetIsFound() {
        Fixture fixture = fixture(new DamageResult(30.0D, false), 1, 100.0D);
        when(fixture.targeting.inCone(same(fixture.player), eq(6.0D), eq(40.0D), eq(1), eq(true))).thenReturn(List.of());
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(75.0D))).thenReturn(75.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(75.0D));
        verify(fixture.astPlayer).setStatusSnapshot(same(fixture.afterManaSnapshot));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: Lv.1〜4ではMPが最大値でない場合に発動を拒否し、MP消費・Shield回復・攻撃を一切行わない。
     */
    @Test
    void rejectsLevelsOneToFourWhenManaIsNotFull() {
        Fixture fixture = fixture(new DamageResult(30.0D, false), 4, 99.0D);

        assertFalse(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat, never()).recoverShield(any(AstEntity.class), any(Double.class));
        verify(fixture.combat, never()).hit(
                any(AstEntity.class), any(AstEntity.class), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(2.50D)
        );
        verify(fixture.astPlayer, never()).setStatusSnapshot(any(StatusSnapshot.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: Lv.5は最大MP100の80%以上で発動でき、4段のソウルファイア防壁演出を予約して現在MPを全量消費する。
     */
    @Test
    void allowsLevelFiveAtEightyPercentManaAndSchedulesSoulBastionPulses() {
        Fixture fixture = fixture(new DamageResult(30.0D, false), 5, 80.0D);
        when(fixture.combat.recoverShield(any(AstEntity.class), eq(75.0D))).thenReturn(75.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanBastionStrikeExecutor.ID + ":soul-bastion"),
                eq(0L),
                eq(2L),
                eq(4),
                any()
        );
        verify(fixture.astPlayer).setStatusSnapshot(same(fixture.afterManaSnapshot));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: Lv.5でも最大MP100の80%未満では発動を拒否し、MP消費・Shield回復・攻撃を一切行わない。
     */
    @Test
    void rejectsLevelFiveBelowEightyPercentMana() {
        Fixture fixture = fixture(new DamageResult(30.0D, false), 5, 79.0D);

        assertFalse(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat, never()).recoverShield(any(AstEntity.class), any(Double.class));
        verify(fixture.astPlayer, never()).setStatusSnapshot(any(StatusSnapshot.class));
    }

    /** バスティオンストライクの発動テストに必要なモック構成を作成します。 */
    private static Fixture fixture(DamageResult result, int skillLevel, double currentMana) {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                mock(SkillEffectService.class),
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);

        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        StatusSnapshot afterManaSnapshot = mock(StatusSnapshot.class);
        when(snapshot.getCurrentShield()).thenReturn(25.0D);
        when(snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(100.0D);
        when(snapshot.getCurrentMp()).thenReturn(currentMana);
        when(snapshot.getMaxValue(StatusType.MAX_MANA)).thenReturn(100.0D);
        when(snapshot.getCurrentHp()).thenReturn(120.0D);
        when(snapshot.withCurrentValues(120.0D, 0.0D)).thenReturn(afterManaSnapshot);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getStatusSnapshot()).thenReturn(snapshot);

        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(world, 0.0D, 64.0D, 4.0D));
        when(targeting.inCone(same(player), eq(6.0D), eq(40.0D), eq(1), eq(true))).thenReturn(List.of(target));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(2.50D)))
                .thenReturn(result);

        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                snapshot,
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH,
                new LearnedSkillInstance(
                        UUID.randomUUID(), UUID.randomUUID(), SwordsmanBastionStrikeExecutor.ID, skillLevel, List.of(), 0, null, null
                )
        );
        return new Fixture(player, targeting, astPlayer, target, combat, tasks, afterManaSnapshot, new SwordsmanBastionStrikeExecutor(services), context);
    }

    /** バスティオンストライクのレベル1マスタ定義を作成します。 */
    private static SkillDefinition definition() {
        return new SkillDefinition(
                SwordsmanBastionStrikeExecutor.ID,
                SwordsmanBastionStrikeExecutor.ID,
                "バスティオンストライク",
                null,
                "SOUL_CAMPFIRE",
                List.of(),
                3000L,
                0.0D,
                0L,
                1,
                "block.beacon.power_select",
                Map.of(
                        "range", 6.0D,
                        "targetAngle", 40.0D,
                        "damageRatio", 2.50D,
                        "consumeAllCurrentMana", true,
                        "levelFiveRequiredManaRatio", 0.80D
                ),
                List.of("active", "melee"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                0.0D,
                null,
                5
        );
    }

    private record Fixture(
            Player player,
            SkillTargetingService targeting,
            AstPlayer astPlayer,
            AstEntity target,
            SkillCombatService combat,
            SkillTaskService tasks,
            StatusSnapshot afterManaSnapshot,
            SwordsmanBastionStrikeExecutor executor,
            SkillCastContext context
    ) {
    }
}
