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
