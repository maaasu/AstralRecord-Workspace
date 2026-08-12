package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionRejectReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConditionServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 1. `ConditionService.applyCondition` > ### 1.2 付与確率
     * 検証契約: 基礎確率・増加率・耐性率を乗算し0〜100へclampする。
     */
    @Test
    void applyChanceUsesIncreaseAndResistanceAsPercentages() {
        assertEquals(45.0D, ConditionService.calculateApplyChance(50.0D, 20.0D, 25.0D), 0.0001D);
        assertEquals(100.0D, ConditionService.calculateApplyChance(80.0D, 100.0D, 0.0D), 0.0001D);
        assertEquals(0.0D, ConditionService.calculateApplyChance(100.0D, 0.0D, 100.0D), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 1. `ConditionService.applyCondition` > ### 1.3 新規付与と同種更新
     * 検証契約: 弱い再付与では効果を維持して期限だけ延長し、強い再付与では効果を置換して既存の長い期限を維持する。
     */
    @Test
    void keepsStrongerEffectAndOnlyExtendsExpiry() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        ActiveCondition first = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 2.0D, 10.0D
        )).condition();
        ActiveCondition afterWeaker = service.applyCondition(request(
            target, null, ConditionType.BURNING, 200L, 1.0D, 5.0D
        )).condition();

        assertEquals(2.0D, afterWeaker.strength(), 0.0001D);
        assertEquals(10.0D, afterWeaker.snapshotPower(), 0.0001D);
        assertTrue(afterWeaker.expiresAtMs() > first.startedAtMs() + 100L * 50L);

        long extendedExpiry = afterWeaker.expiresAtMs();
        ActiveCondition afterStrongerShorter = service.applyCondition(request(
            target, null, ConditionType.BURNING, 50L, 3.0D, 15.0D
        )).condition();

        assertEquals(3.0D, afterStrongerShorter.strength(), 0.0001D);
        assertEquals(15.0D, afterStrongerShorter.snapshotPower(), 0.0001D);
        assertEquals(extendedExpiry, afterStrongerShorter.expiresAtMs());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/27_1-モデル定義.md
     * 章・見出し: # 27_1-モデル定義 > ## 2. `ConditionEffect`
     * 検証契約: 炎上は攻撃種別の解決攻撃力（ATTACK と種別攻撃力の合計へ基本能力値倍率を適用）を基準にし、固定値や係数上書きで種別既定係数の上限を超えない。
     */
    @Test
    void burningSnapshotUsesResolvedAttackPowerWithDotCoefficientCap() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.ATTACK.name(), 100.0D),
            new MobBaseStat(StatusType.INTELLIGENCE.name(), 50.0D),
            new MobBaseStat(StatusType.MAGIC_ATTACK.name(), 20.0D)
        )));

        ActiveCondition condition = service.applyCondition(new ConditionApplyRequest(
            target,
            source,
            ConditionType.BURNING,
            AttackType.MAGIC,
            100L,
            100.0D,
            1.0D,
            999.0D,
            1.0D,
            null,
            null,
            ConditionApplyReason.SKILL
        )).condition();

        assertEquals(36.0D, condition.snapshotPower(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/27_1-モデル定義.md
     * 章・見出し: # 27_1-モデル定義 > ## 6. 状態異常ステータス
     * 検証契約: 毒は対象HPではなく付与元のSUPPORT_POWERを基準にする。
     */
    @Test
    void poisonSnapshotUsesSupportPowerInsteadOfTargetHealth() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.SUPPORT_POWER.name(), 100.0D)
        )));

        ActiveCondition condition = service.applyCondition(new ConditionApplyRequest(
            target,
            source,
            ConditionType.POISON,
            null,
            120L,
            100.0D,
            1.0D,
            null,
            null,
            1.0D,
            null,
            ConditionApplyReason.SKILL
        )).condition();

        assertEquals(16.0D, condition.snapshotPower(), 0.0001D);
        assertEquals(0.0D, condition.healthRate(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/27_0-概要.md
     * 章・見出し: # 27_0-概要 > ## 3. 不変条件
     * 検証契約: 冷気を残したまま凍結で移動不可とし、凍結解除後は冷気の移動倍率0.5へ戻す。
     */
    @Test
    void frozenDominatesChilledWithoutRemovingIt() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        service.applyCondition(request(target, null, ConditionType.CHILLED, 100L, 1.0D, null));
        assertEquals(0.5D, service.movementSpeedMultiplier(target), 0.0001D);

        service.applyCondition(request(target, null, ConditionType.FROZEN, 40L, 1.0D, null));
        assertFalse(service.canMove(target));
        assertTrue(service.getActiveConditions(target).stream()
            .anyMatch(condition -> condition.type() == ConditionType.CHILLED));

        service.removeCondition(target, ConditionType.FROZEN);
        assertTrue(service.canMove(target));
        assertEquals(0.5D, service.movementSpeedMultiplier(target), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 4. `conditionDamageMultiplier`
     * 検証契約: DoT増加20%、耐性50%、貫通10%を専用式で0.72倍へ計算する。
     */
    @Test
    void conditionDamageUsesIndependentIncreaseResistanceAndPenetration() {
        ConditionService service = service();
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_INCREASE.name(), 20.0D),
            new MobBaseStat(StatusType.BURNING_DAMAGE_PENETRATION.name(), 10.0D)
        )));
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_RESISTANCE.name(), 50.0D)
        )));

        assertEquals(0.72D, service.conditionDamageMultiplier(source, target, ConditionType.BURNING), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 4. `conditionDamageMultiplier`
     * 検証契約: DoT耐性-25%を弱点として1.25倍へ増幅する。
     */
    @Test
    void negativeDotResistanceIncreasesConditionDamage() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_RESISTANCE.name(), -25.0D)
        )));

        assertEquals(1.25D, service.conditionDamageMultiplier(null, target, ConditionType.BURNING), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 1. `ConditionService.applyCondition` > ### 1.2 付与確率
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 3. 行動可否と倍率
     * 検証契約: 付与耐性100%ではCHANCE_FAILEDとなり、回復阻害が有効な対象はhealing blockedとなる。
     */
    @Test
    void fullApplyResistanceAlwaysRejectsAndHealingInhibitionBlocksRecovery() {
        ConditionService service = service();
        AstEntity resistant = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.FROZEN_RESISTANCE.name(), 100.0D)
        )));
        var rejected = service.applyCondition(request(
            resistant, null, ConditionType.FROZEN, 40L, 1.0D, null
        ));
        assertFalse(rejected.success());
        assertEquals(ConditionRejectReason.CHANCE_FAILED, rejected.rejectReason());

        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        service.applyCondition(request(target, null, ConditionType.HEALING_INHIBITION, 100L, 1.0D, null));
        assertTrue(service.isHealingBlocked(target));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 3. 行動可否と倍率
     * 検証契約: 衰弱中のsourceの全与ダメージ倍率を0.5とする。
     */
    @Test
    void weaknessHalvesAllOutgoingDamageIncludingConditionSourceDamage() {
        ConditionService service = service();
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        service.applyCondition(request(source, null, ConditionType.WEAKNESS, 100L, 1.0D, null));

        assertEquals(0.5D, service.damageDealtMultiplier(source), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-サービス.md
     * 章・見出し: # 27_3-サービス > ## 2. 解除・照会・全体掃除
     * 検証契約: 指定時刻で期限到達した状態異常をmapから削除し、その後は新規状態として再付与できる。
     */
    @Test
    void cleanupSweepRemovesConditionsExpiredAtProvidedTime() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        ActiveCondition condition = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 1.0D, null
        )).condition();

        assertEquals(1, service.purgeExpiredConditions(condition.expiresAtMs()));
        assertTrue(service.snapshotAllActiveConditions().isEmpty());

        var reapplied = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 1.0D, null
        ));
        assertTrue(reapplied.success());
        assertFalse(reapplied.updated());
    }

    private ConditionService service() {
        return new ConditionService(mock(ConditionDisplayService.class), null);
    }

    private ConditionApplyRequest request(
            AstEntity target,
            AstEntity source,
            ConditionType type,
            long durationTicks,
            double strength,
            Double basePower
    ) {
        return new ConditionApplyRequest(
            target,
            source,
            type,
            durationTicks,
            100.0D,
            strength,
            basePower,
            null,
            null,
            null,
            ConditionApplyReason.SKILL
        );
    }

    private MobInstance mob(MobCategory category, List<MobBaseStat> stats) {
        MobTemplate template = new MobTemplate(
            1,
            "condition_test_" + UUID.randomUUID(),
            category,
            "Condition Test",
            null,
            1,
            EntityType.IRON_GOLEM,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            stats,
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
        return new MobInstance(UUID.randomUUID(), template, new Location(null, 0.0D, 0.0D, 0.0D));
    }
}
