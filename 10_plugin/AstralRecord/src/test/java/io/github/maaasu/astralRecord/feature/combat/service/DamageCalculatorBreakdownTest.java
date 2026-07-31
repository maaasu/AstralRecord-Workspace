package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
