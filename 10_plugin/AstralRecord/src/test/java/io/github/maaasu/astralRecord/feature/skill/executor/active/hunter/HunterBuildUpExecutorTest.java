package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
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
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HunterBuildUpExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: ビルドアップは専用buff参照を必須とし、別のbuffまたは未定義の参照を受け付けない。
     */
    @Test
    void validatesOnlyTheBuildUpBuffReference() {
        HunterBuildUpExecutor executor = new HunterBuildUpExecutor(activeSkillServices());

        executor.validateParams(definition(params()));

        SkillParameterException missing = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(paramsWithoutBuffId()))
        );
        assertEquals("buffId", missing.key());

        SkillParameterException different = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(params(Map.of("buffId", "buff:attack_up_small"))))
        );
        assertEquals("buffId", different.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: 毎秒ENG回復はLv.1からLv.5の1%から5%に限り、間接攻撃力補正は10%に同じ割合を加える。
     */
    @Test
    void validatesLevelDependentRecoveryAndAttackIncreaseRatios() {
        HunterBuildUpExecutor executor = new HunterBuildUpExecutor(activeSkillServices());

        executor.validateParams(definition(params(Map.of(
                "energyRecoveryRatio", 0.05D,
                "rangedAttackIncreaseRatio", 0.15D
        ))));

        SkillParameterException recovery = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(params(Map.of(
                        "energyRecoveryRatio", 0.06D,
                        "rangedAttackIncreaseRatio", 0.16D
                ))))
        );
        assertEquals("energyRecoveryRatio", recovery.key());

        SkillParameterException attack = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(params(Map.of(
                        "energyRecoveryRatio", 0.05D,
                        "rangedAttackIncreaseRatio", 0.14D
                ))))
        );
        assertEquals("rangedAttackIncreaseRatio", attack.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: 発動成功時は発動者へhunter_build_upを適用し、共通発動制御へ成功結果を返す。
     */
    @Test
    void appliesBuildUpBuffAndReturnsSuccess() {
        AstPlayer player = player(UUID.randomUUID());
        StatusService statusService = mock(StatusService.class);
        ActiveBuff activeBuff = mock(ActiveBuff.class);
        BuffType buffType = mock(BuffType.class);
        when(activeBuff.getType()).thenReturn(buffType);
        when(buffType.getId()).thenReturn(HunterBuildUpExecutor.ID);
        when(statusService.getActiveBuffs(same(player))).thenReturn(List.of(activeBuff));

        SkillCombatService combat = new SkillCombatService(
                mock(DamageService.class),
                mock(ConditionService.class),
                mock(MobKnockbackService.class),
                statusService
        );
        HunterBuildUpExecutor executor = new HunterBuildUpExecutor(activeSkillServices(combat));

        SkillCastResult result = executor.cast(context(player));

        assertTrue(result.success());
        verify(statusService).applyBuff(same(player), eq(HunterBuildUpExecutor.ID));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: バフ付与後に有効なhunter_build_upが確認できない場合、ビルドアップは失敗結果を返す。
     */
    @Test
    void failsWhenBuildUpBuffWasNotApplied() {
        AstPlayer player = player(UUID.randomUUID());
        StatusService statusService = mock(StatusService.class);
        when(statusService.getActiveBuffs(same(player))).thenReturn(List.of());

        SkillCombatService combat = new SkillCombatService(
                mock(DamageService.class),
                mock(ConditionService.class),
                mock(MobKnockbackService.class),
                statusService
        );
        HunterBuildUpExecutor executor = new HunterBuildUpExecutor(activeSkillServices(combat));

        SkillCastResult result = executor.cast(context(player));

        assertFalse(result.success());
        verify(statusService).applyBuff(same(player), eq(HunterBuildUpExecutor.ID));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: Lv.5では専用buffを付与し、1秒後から10回、毎秒最大ENGの5%を回復する。
     */
    @Test
    void appliesLevelFiveBuffAndSchedulesTenEnergyRecoveryTicks() {
        UUID playerId = UUID.randomUUID();
        AstPlayer player = player(playerId);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        when(combat.applyBuff(same(player), eq("hunter_build_up_lv5"))).thenReturn(true);
        HunterBuildUpExecutor executor = new HunterBuildUpExecutor(activeSkillServices(combat, tasks));

        SkillCastResult result = executor.cast(context(player, params(Map.of(
                "energyRecoveryRatio", 0.05D,
                "rangedAttackIncreaseRatio", 0.15D
        ))));

        assertTrue(result.success());
        verify(combat).applyBuff(same(player), eq("hunter_build_up_lv5"));
        ArgumentCaptor<IntConsumer> recoveryTick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(
                eq(playerId),
                eq("hunter_build_up:energy_recovery"),
                eq(20L),
                eq(20L),
                eq(10),
                recoveryTick.capture()
        );
        for (int index = 0; index < 10; index++) {
            recoveryTick.getValue().accept(index);
        }
        verify(combat, times(10)).recoverEnergyByMaxRatio(same(player), eq(0.05D));
    }

    private static ActiveSkillServices activeSkillServices() {
        return activeSkillServices(mock(SkillCombatService.class));
    }

    private static ActiveSkillServices activeSkillServices(SkillCombatService combat) {
        return activeSkillServices(combat, mock(SkillTaskService.class));
    }

    private static ActiveSkillServices activeSkillServices(SkillCombatService combat, SkillTaskService tasks) {
        return new ActiveSkillServices(
                mock(SkillTargetingService.class),
                combat,
                mock(SkillEffectService.class),
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
    }

    private static SkillCastContext context(AstPlayer player) {
        return context(player, params());
    }

    private static SkillCastContext context(AstPlayer player, Map<String, Object> params) {
        return new SkillCastContext(
                definition(params),
                new PlayerSkillCaster(player),
                null,
                List.of(),
                new Location(mock(World.class), 0.0D, 64.0D, 0.0D),
                mock(StatusSnapshot.class),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
                HunterBuildUpExecutor.ID,
                HunterBuildUpExecutor.ID,
                "ビルドアップ",
                null,
                "TIPPED_ARROW",
                List.of(),
                300L,
                6.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "ranged", "bow"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                6.0D,
                null,
                5
        );
    }

    private static AstPlayer player(UUID playerId) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(astPlayer.getBukkit()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getUniqueId()).thenReturn(playerId);
        return astPlayer;
    }

    private static Map<String, Object> params() {
        return params(Map.of());
    }

    private static Map<String, Object> params(Map<String, Object> overrides) {
        Map<String, Object> params = new HashMap<>();
        params.put("buffId", "buff:hunter_build_up");
        params.put("energyRecoveryRatio", 0.01D);
        params.put("rangedAttackIncreaseRatio", 0.11D);
        params.putAll(overrides);
        return params;
    }

    private static Map<String, Object> paramsWithoutBuffId() {
        Map<String, Object> params = params();
        params.remove("buffId");
        return params;
    }
}
