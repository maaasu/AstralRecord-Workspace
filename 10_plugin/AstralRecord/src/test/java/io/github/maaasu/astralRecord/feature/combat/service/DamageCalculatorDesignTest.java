package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCalculatorDesignTest {

    @Test
    void attackerStatusScalingUsesAttackPrimaryTypedAttackAndDefense() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        AstPlayer attacker = player(Map.of(
            StatusType.ATTACK, 20.0D,
            StatusType.STRENGTH, 50.0D,
            StatusType.MELEE_ATTACK, 5.0D,
            StatusType.ACCURACY, 100.0D
        ));
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
        AstPlayer attacker = player(Map.of(
            StatusType.CRITICAL_RATE, 100.0D,
            StatusType.CRITICAL_DAMAGE, 200.0D,
            StatusType.SUPER_CRITICAL_RATE, 100.0D,
            StatusType.SUPER_CRITICAL_DAMAGE, 150.0D,
            StatusType.ACCURACY, 100.0D
        ));
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

    @Test
    void accuracyMinusEvasionCanProduceEvadedResult() {
        DamageCalculator calculator = new DamageCalculator(() -> 90.0D, () -> 100.0D);
        AstPlayer attacker = player(Map.of(
            StatusType.ATTACK, 10.0D,
            StatusType.ACCURACY, 95.0D
        ));
        AstPlayer victim = player(Map.of(
            StatusType.EVASION, 10.0D
        ));

        var result = calculator.calculate(new DamageContext(
            AstEntity.player(attacker),
            AstEntity.player(victim),
            0.0D,
            AttackType.MELEE,
            DamageType.PHYSICAL,
            DamageScaling.ATTACKER_STATUS
        ));

        assertTrue(result.evaded());
        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(85.0D, result.hitChance(), 0.0001D);
        assertEquals(95.0D, result.accuracy(), 0.0001D);
        assertEquals(10.0D, result.evasion(), 0.0001D);
    }

    @Test
    void fixedDamageDoesNotRunEvasionCheck() {
        DamageCalculator calculator = new DamageCalculator(() -> 99.0D, () -> 100.0D);
        AstPlayer attacker = player(Map.of(
            StatusType.ACCURACY, 1.0D
        ));
        AstPlayer victim = player(Map.of(
            StatusType.EVASION, 100.0D
        ));

        var result = calculator.calculate(new DamageContext(
            AstEntity.player(attacker),
            AstEntity.player(victim),
            12.0D,
            AttackType.MAGIC,
            DamageType.TRUE,
            DamageScaling.FIXED
        ));

        assertFalse(result.evaded());
        assertEquals(12.0D, result.finalDamage(), 0.0001D);
        assertEquals(100.0D, result.hitChance(), 0.0001D);
    }

    @Test
    void levelDifferenceScalesDamageAndClampsAtConfiguredBounds() {
        DamageCalculator calculator = new DamageCalculator(() -> 100.0D);
        AstPlayer lowLevelAttacker = player(Map.of(), 1);
        MobInstance highLevelVictim = DesignTestFixtures.mobInstance(11, 100.0D, 0.0D, 0.0D, null);

        var lowResult = calculator.calculate(new DamageContext(
            AstEntity.player(lowLevelAttacker),
            AstEntity.mob(highLevelVictim),
            100.0D,
            AttackType.MELEE,
            DamageType.TRUE,
            DamageScaling.FIXED
        ));

        AstPlayer highLevelAttacker = player(Map.of(), 11);
        MobInstance lowLevelVictim = DesignTestFixtures.mobInstance(1, 100.0D, 0.0D, 0.0D, null);
        var highResult = calculator.calculate(new DamageContext(
            AstEntity.player(highLevelAttacker),
            AstEntity.mob(lowLevelVictim),
            100.0D,
            AttackType.MELEE,
            DamageType.TRUE,
            DamageScaling.FIXED
        ));

        assertEquals(70.0D, lowResult.finalDamage(), 0.0001D);
        assertEquals(130.0D, highResult.finalDamage(), 0.0001D);
        assertEquals(0.70D, LevelDifferenceCalculator.damageMultiplier(1, 100), 0.0001D);
        assertEquals(1.30D, LevelDifferenceCalculator.damageMultiplier(100, 1), 0.0001D);
    }

    private AstPlayer player(Map<StatusType, Double> statuses) {
        return player(statuses, 1);
    }

    private AstPlayer player(Map<StatusType, Double> statuses, int level) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
            userId,
            "test-player",
            now,
            now,
            "127.0.0.1",
            accountId,
            false,
            null,
            false,
            0,
            now,
            now,
            systemId,
            systemId,
            false
        );
        AccountModel account = new AccountModel(
            accountId,
            userId,
            "test-account",
            0,
            true,
            AccountMode.ADMIN,
            "{}",
            now,
            now,
            systemId,
            systemId,
            false,
            level
        );
        AstPlayer player = new AstPlayer(bukkitPlayer(), user, account);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(statuses, 100.0D, 0.0D, 0.0D));
        return player;
    }

    private Player bukkitPlayer() {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> null
        );
    }
}
