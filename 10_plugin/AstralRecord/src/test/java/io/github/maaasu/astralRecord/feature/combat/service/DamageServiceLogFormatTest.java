package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageBreakdown;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DamageServiceLogFormatTest {

    @Test
    void compactFormatIncludesDamageTypeAndSingleElementResistance() {
        DamageBreakdown breakdown = new DamageBreakdown(
                180.0D,
                80.0D,
                64.0D,
                List.of(new DamageBreakdown.ElementResistance(DamageElement.FIRE, 25.0D, 15.0D))
        );
        DamageResult result = new DamageResult(
                125.0D,
                true,
                92.0D,
                95.0D,
                3.0D,
                breakdown
        );

        assertEquals("&cHP125", DamageService.damageSummary(result));
        assertEquals("MEL", DamageService.attackTypeCode(AttackType.MELEE));
        assertEquals("FIR", DamageService.damageElementsCode(
                List.of(new DamageComponent(DamageElement.FIRE, 1.0D))
        ));
        assertEquals(" RES25>15", DamageService.resistanceSummary(breakdown));
        assertEquals("92", DamageService.formatCompactNumber(result.hitChance()));
        assertEquals(" &eCRIT", DamageService.criticalSummary(result));
    }

    @Test
    void multipleElementResistancesIncludeElementCodes() {
        DamageBreakdown breakdown = new DamageBreakdown(
                100.0D,
                0.0D,
                0.0D,
                List.of(
                        new DamageBreakdown.ElementResistance(DamageElement.FIRE, 25.0D, 15.0D),
                        new DamageBreakdown.ElementResistance(DamageElement.ICE, 10.0D, 5.0D)
                )
        );

        assertEquals(" RES[F25>15/I10>5]", DamageService.resistanceSummary(breakdown));
    }

    @Test
    void shieldResultKeepsCalculationBreakdownAndShowsBreakMarker() {
        DamageBreakdown breakdown = new DamageBreakdown(90.0D, 42.0D, 42.0D, List.of());
        DamageResult calculated = new DamageResult(48.0D, false, 76.0D, 82.0D, 6.0D, breakdown);
        DamageResult shield = DamageResult.shield(3.0D, true, calculated);

        assertSame(breakdown, shield.breakdown());
        assertEquals("&bSHD3!", DamageService.damageSummary(shield));
    }

    @Test
    void superStarCriticalHasIndependentCompactMarker() {
        DamageBreakdown breakdown = DamageBreakdown.empty();
        DamageResult superStar = new DamageResult(125.0D, false, true, 100.0D, 100.0D, 0.0D, breakdown);
        DamageResult both = new DamageResult(125.0D, true, true, 100.0D, 100.0D, 0.0D, breakdown);

        assertEquals(" &dS-CRIT", DamageService.criticalSummary(superStar));
        assertEquals(" &eCRIT&d+S-CRIT", DamageService.criticalSummary(both));
    }
}
