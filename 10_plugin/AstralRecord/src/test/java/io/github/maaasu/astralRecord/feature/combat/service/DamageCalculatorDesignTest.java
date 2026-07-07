package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCalculatorDesignTest extends MockBukkitTestBase {

    @Test
    void attackerStatusScalingUsesAttackPrimaryTypedAttackAndDefense() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.ATTACK, 20.0D,
            StatusType.STRENGTH, 50.0D,
            StatusType.MELEE_ATTACK, 5.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance victim = DesignTestFixtures.mobInstance(100.0D, 8.0D, 0.0D);

        var result = calculator.calculate(new DamageContext(
            AstEntity.player(attacker),
            AstEntity.mob(victim),
            0.0D,
            AttackType.MELEE,
            DamageType.PHYSICAL,
            DamageScaling.ATTACKER_STATUS
        ));

        assertEquals(31.0D, result.finalDamage(), 0.0001D);
        assertFalse(result.critical());
    }

    @Test
    void criticalAndSuperCriticalUseConfiguredMultipliers() {
        DamageCalculator calculator = new DamageCalculator(() -> 0.0D);
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.CRITICAL_RATE, 100.0D,
            StatusType.CRITICAL_DAMAGE, 200.0D,
            StatusType.SUPER_CRITICAL_RATE, 100.0D,
            StatusType.SUPER_CRITICAL_DAMAGE, 150.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance victim = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);

        var result = calculator.calculate(new DamageContext(
            AstEntity.player(attacker),
            AstEntity.mob(victim),
            10.0D,
            AttackType.MELEE,
            DamageType.PHYSICAL,
            DamageScaling.FIXED
        ));

        assertEquals(30.0D, result.finalDamage(), 0.0001D);
        assertTrue(result.critical());
    }

    @Test
    void trueDamageBypassesDefense() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        MobInstance victim = DesignTestFixtures.mobInstance(100.0D, 999.0D, 999.0D);

        var result = calculator.calculate(new DamageContext(
            null,
            AstEntity.mob(victim),
            12.0D,
            AttackType.MAGIC,
            DamageType.TRUE,
            DamageScaling.FIXED
        ));

        assertEquals(12.0D, result.finalDamage(), 0.0001D);
    }
}
