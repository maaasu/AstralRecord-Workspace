package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaladinDefenseCalculationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 2. 防御低下
     * 検証契約: 全防御と種別防御の貫通後合計に聖痕を乗算し、元ステータスは不変に保つ。
     */
    @Test
    void shredMultipliesBothDefensesAfterPenetration() {
        AstEntity victim = mock(AstEntity.class);
        AstEntity attacker = mock(AstEntity.class);
        when(victim.isManaged()).thenReturn(true);
        when(victim.statValue(StatusType.DEFENSE)).thenReturn(100.0);
        when(victim.statValue(StatusType.MELEE_DEFENSE)).thenReturn(80.0);
        when(attacker.statValue(StatusType.DEFENSE_PENETRATION_RATE)).thenReturn(20.0);
        when(attacker.statValue(StatusType.MELEE_DEFENSE_PENETRATION_RATE)).thenReturn(50.0);
        var context = new DamageContext(attacker, victim, 200.0, AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, 1.0)), DamageScaling.FIXED);
        var calculator = new DamageCalculator();
        var normal = calculator.calculate(context);
        var marked = calculator.calculate(context, 0, .75);
        assertEquals(180, marked.breakdown().rawDefense(), 1e-9);
        assertEquals(90, marked.breakdown().effectiveDefense(), 1e-9);
        assertEquals(120, normal.breakdown().effectiveDefense(), 1e-9);
        assertTrue(marked.finalDamage() > normal.finalDamage());
        assertEquals(normal.finalDamage(), calculator.calculate(context).finalDamage(), 1e-9);
    }
}
