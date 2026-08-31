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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
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

        executor.validateParams(definition(Map.of("buffId", "buff:hunter_build_up")));

        SkillParameterException missing = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(Map.of()))
        );
        assertEquals("buffId", missing.key());

        SkillParameterException different = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(Map.of("buffId", "buff:attack_up_small")))
        );
        assertEquals("buffId", different.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 27. ハンター ビルドアップの実装契約 > ### 27.1 数値・発動
     * 検証契約: 発動成功時は発動者へhunter_build_upを適用し、共通発動制御へ成功結果を返す。
     */
    @Test
    void appliesBuildUpBuffAndReturnsSuccess() {
        AstPlayer player = mock(AstPlayer.class);
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
        AstPlayer player = mock(AstPlayer.class);
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

    private static ActiveSkillServices activeSkillServices() {
        return activeSkillServices(mock(SkillCombatService.class));
    }

    private static ActiveSkillServices activeSkillServices(SkillCombatService combat) {
        return new ActiveSkillServices(
                mock(SkillTargetingService.class),
                combat,
                mock(SkillEffectService.class),
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
    }

    private static SkillCastContext context(AstPlayer player) {
        return new SkillCastContext(
                definition(Map.of("buffId", "buff:hunter_build_up")),
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
                600L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "ranged", "bow"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                10.0D,
                null,
                1
        );
    }
}
