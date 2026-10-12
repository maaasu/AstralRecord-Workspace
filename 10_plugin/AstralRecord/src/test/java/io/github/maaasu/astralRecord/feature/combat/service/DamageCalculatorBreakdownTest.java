package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import java.util.UUID;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DamageCalculatorBreakdownTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_1-モデル定義.md
     * 章・見出し: # 14_1-モデル定義 > ## 6. damage breakdown
     * 検証契約: 同一hit計算で解決attack、貫通前後defense、属性raw/effective resistanceをsnapshot化する。
     */
    @Test
    void calculationCapturesResolvedAttackDefenseAndElementResistance() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        var victim = DesignTestFixtures.mobInstance(100.0D, 8.0D, 0.0D);

        var result = calculator.calculate(new DamageContext(
                null,
                AstEntity.mob(victim),
                40.0D,
                AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.FIRE, 1.0D)),
                DamageScaling.FIXED
        ));

        assertEquals(40.0D, result.breakdown().resolvedAttackPower(), 0.0001D);
        assertEquals(8.0D, result.breakdown().rawDefense(), 0.0001D);
        assertEquals(8.0D, result.breakdown().effectiveDefense(), 0.0001D);
        assertEquals(1, result.breakdown().elementResistances().size());
        var resistance = result.breakdown().elementResistances().getFirst();
        assertEquals(DamageElement.FIRE, resistance.element());
        assertEquals(0.0D, resistance.rawResistance(), 0.0001D);
        assertEquals(0.0D, resistance.effectiveResistance(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_1-モデル定義.md
     * 章・見出し: # 14_1-モデル定義 > ## 6. damage breakdown
     * 検証契約: component ratio合計0でdamage 0でも解決attack/defense breakdownを保持する。
     */
    @Test
    void zeroRatioResultStillKeepsAttackAndDefenseBreakdown() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        var victim = DesignTestFixtures.mobInstance(100.0D, 8.0D, 0.0D);

        var result = calculator.calculate(new DamageContext(
                null,
                AstEntity.mob(victim),
                40.0D,
                AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, 0.0D)),
                DamageScaling.FIXED
        ));

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(40.0D, result.breakdown().resolvedAttackPower(), 0.0001D);
        assertEquals(8.0D, result.breakdown().rawDefense(), 0.0001D);
        assertEquals(8.0D, result.breakdown().effectiveDefense(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 無属性ダメージ増加と耐性は無属性成分だけへ適用し、火属性成分と独立した内訳を保持する。
     */
    @Test
    void neutralIncreaseAndResistanceAffectOnlyNeutralShare() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        AstPlayer player = mock(AstPlayer.class);
        var attacker = AstEntity.player(player, DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.NONE_DAMAGE_INCREASE, 25.0D), 100.0D, 100.0D, 100.0D
        ));
        var victim = mobWithStats(Map.of(StatusType.NONE_RESISTANCE, 20.0D));

        var result = calculator.calculate(new DamageContext(
                attacker, victim, 40.0D, AttackType.MAGIC,
                List.of(new DamageComponent(DamageElement.NONE, 0.5D),
                        new DamageComponent(DamageElement.FIRE, 0.5D)),
                DamageScaling.FIXED
        ));

        assertEquals(40.0D, result.finalDamage(), 0.0001D);
        assertEquals(2, result.breakdown().elementResistances().size());
        var neutral = result.breakdown().elementResistances().getFirst();
        assertEquals(DamageElement.NONE, neutral.element());
        assertEquals(20.0D, neutral.rawResistance(), 0.0001D);
        assertEquals(20.0D, neutral.effectiveResistance(), 0.0001D);
        assertEquals(DamageElement.FIRE, result.breakdown().elementResistances().get(1).element());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: Mobで耐性上限が未指定なら無属性50%、火属性75%を適用し、使用した値を内訳へ保存する。
     */
    @Test
    void missingMobCapsLimitNeutralAndFireResistanceIndependently() {
        var victim = mobWithStats(Map.of(StatusType.NONE_RESISTANCE, 90.0D,
                StatusType.FIRE_RESISTANCE, 90.0D));
        var result = new DamageCalculator(() -> 100.0D).calculate(new DamageContext(
                null, victim, 100.0D, AttackType.MAGIC,
                List.of(new DamageComponent(DamageElement.NONE, 0.5D),
                        new DamageComponent(DamageElement.FIRE, 0.5D)), DamageScaling.FIXED
        ));
        assertEquals(37.5D, result.finalDamage(), 0.0001D);
        assertEquals(50.0D, result.breakdown().elementResistances().get(0).effectiveResistance(), 0.0001D);
        assertEquals(75.0D, result.breakdown().elementResistances().get(1).effectiveResistance(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: Mobの無属性耐性上限は明示した0や基礎値を超える値を未指定fallbackで上書きしない。
     */
    @Test
    void explicitMobNeutralCapsOverrideTheFallbackIncludingZero() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        for (double cap : new double[]{0.0D, 60.0D}) {
            var victim = mobWithStats(Map.of(StatusType.NONE_RESISTANCE, 90.0D,
                    StatusType.NONE_RESISTANCE_CAP, cap));
            var result = calculator.calculate(neutralContext(victim));
            assertEquals(cap == 0.0D ? 100.0D : 40.0D, result.finalDamage(), 0.0001D);
            assertEquals(cap, result.breakdown().elementResistances().getFirst().effectiveResistance(), 0.0001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: プレイヤーの無属性耐性は最終statusの上限で制限し、負の耐性は弱点としてdamageへ反映する。
     */
    @Test
    void playerNeutralResistanceUsesFinalCapAndPreservesWeaknesses() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        for (double resistance : new double[]{80.0D, -25.0D}) {
            var victim = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(
                    Map.of(StatusType.NONE_RESISTANCE, resistance, StatusType.NONE_RESISTANCE_CAP, 20.0D),
                    100.0D, 100.0D, 100.0D));
            var result = calculator.calculate(neutralContext(victim));
            assertEquals(resistance > 0.0D ? 80.0D : 125.0D, result.finalDamage(), 0.0001D);
            var breakdown = result.breakdown().elementResistances().getFirst();
            assertEquals(resistance, breakdown.rawResistance(), 0.0001D);
            assertEquals(resistance > 0.0D ? 20.0D : -25.0D, breakdown.effectiveResistance(), 0.0001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 属性増加分の独立加算へ無属性増加・耐性を重ねず、当該属性の貫通後耐性だけを適用する。
     */
    @Test
    void additiveElementShareDoesNotInheritNeutralIncreaseOrResistance() {
        var attacker = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.NONE_DAMAGE_INCREASE, 50.0D,
                        StatusType.FIRE_DAMAGE_INCREASE, 50.0D,
                        StatusType.FIRE_PENETRATION, 10.0D), 100.0D, 100.0D, 100.0D));
        var victim = mobWithStats(Map.of(StatusType.NONE_RESISTANCE, 90.0D,
                StatusType.FIRE_RESISTANCE, 30.0D));
        var context = new DamageContext(attacker, victim, 100.0D, AttackType.MAGIC,
                List.of(DamageComponent.defaultComponent()), DamageScaling.FIXED);
        var result = new DamageCalculator(() -> 100.0D).calculate(context, 0.0D, false,
                1.0D, 1.0D, Set.of(DamageElement.NONE, DamageElement.FIRE));
        // 無属性基礎成分75と火属性加算成分40。NONE自体は加算対象にしません。
        assertEquals(115.0D, result.finalDamage(), 0.0001D);
        assertEquals(115.0D, result.breakdown().preDefenseDamage(), 0.0001D);
        assertEquals(2, result.breakdown().elementResistances().size());
        var fire = result.breakdown().elementResistances().get(1);
        assertEquals(DamageElement.FIRE, fire.element());
        assertEquals(30.0D, fire.rawResistance(), 0.0001D);
        assertEquals(20.0D, fire.effectiveResistance(), 0.0001D);
    }

    private DamageContext neutralContext(AstEntity victim) {
        return new DamageContext(null, victim, 100.0D, AttackType.MAGIC,
                List.of(DamageComponent.defaultComponent()), DamageScaling.FIXED);
    }

    private AstEntity mobWithStats(Map<StatusType, Double> stats) {
        MobInstance source = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        MobTemplate original = source.template();
        MobTemplate template = new MobTemplate(
                original.schemaVersion(), original.id(), original.category(), original.displayName(),
                original.title(), original.level(), original.entityType(), original.nameVisible(),
                original.icon(), original.lore(), original.tags(), original.skin(), original.equipment(),
                stats.entrySet().stream().map(entry -> new MobBaseStat(entry.getKey().name(), entry.getValue())).toList(),
                original.shield(), original.idle(), original.damageImmune(), original.interactions(),
                original.targeting(), original.combat(), original.drops()
        );
        return AstEntity.mob(new MobInstance(UUID.randomUUID(), template, source.currentLocation()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 一時回避率倍率は命中判定へ反映し、防御倍率とは独立して扱う。
     */
    @Test
    void temporaryEvasionMultiplierChangesHitChance() {
        DamageCalculator calculator = new DamageCalculator(() -> 80.0D);
        AstEntity attacker = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.ACCURACY, 95.0D, StatusType.ATTACK, 100.0D), 100.0D, 100.0D, 100.0D
        ));
        AstEntity victim = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.EVASION, 20.0D), 100.0D, 100.0D, 100.0D
        ));
        DamageContext context = new DamageContext(
                attacker, victim, 100.0D, AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, 1.0D)), DamageScaling.ATTACKER_STATUS
        );

        var normal = calculator.calculate(context, 0.0D, false, 1.0D, 1.0D);
        var exposed = calculator.calculate(context, 0.0D, false, 1.0D, 0.15D);

        assertEquals(75.0D, normal.hitChance(), 0.0001D);
        assertEquals(20.0D, normal.evasion(), 0.0001D);
        assertEquals(92.0D, exposed.hitChance(), 0.0001D);
        assertEquals(3.0D, exposed.evasion(), 0.0001D);
    }
}
