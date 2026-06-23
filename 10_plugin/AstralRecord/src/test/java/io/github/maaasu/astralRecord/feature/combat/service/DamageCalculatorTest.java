package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCalculatorTest {

    @Test
    void criticalHitMarksResultAndAppliesCriticalDamage() {
        DamageCalculator calculator = new DamageCalculator(new SequenceDoubleSupplier(0.0D));
        AstEntity attacker = AstEntity.mob(mobWithStats(
                stat(StatusType.CRITICAL_RATE, 100.0D),
                stat(StatusType.CRITICAL_DAMAGE, 200.0D)
        ));
        AstEntity victim = AstEntity.mob(mobWithStats());

        DamageResult result = calculator.calculate(new DamageContext(
                attacker,
                victim,
                20.0D,
                AttackType.MELEE,
                DamageType.PHYSICAL,
                DamageScaling.FIXED
        ));

        assertEquals(40.0D, result.finalDamage());
        assertTrue(result.critical());
    }

    @Test
    void superCriticalHitAddsSuperCriticalDamage() {
        DamageCalculator calculator = new DamageCalculator(new SequenceDoubleSupplier(0.0D, 0.0D));
        AstEntity attacker = AstEntity.mob(mobWithStats(
                stat(StatusType.CRITICAL_RATE, 100.0D),
                stat(StatusType.CRITICAL_DAMAGE, 200.0D),
                stat(StatusType.SUPER_CRITICAL_RATE, 100.0D),
                stat(StatusType.SUPER_CRITICAL_DAMAGE, 300.0D)
        ));
        AstEntity victim = AstEntity.mob(mobWithStats());

        DamageResult result = calculator.calculate(new DamageContext(
                attacker,
                victim,
                20.0D,
                AttackType.MELEE,
                DamageType.PHYSICAL,
                DamageScaling.FIXED
        ));

        assertEquals(120.0D, result.finalDamage());
        assertTrue(result.critical());
    }

    private static MobBaseStat stat(StatusType type, double value) {
        return new MobBaseStat(type.name(), value);
    }

    private static MobInstance mobWithStats(MobBaseStat... stats) {
        MobTemplate template = new MobTemplate(
                1,
                "damage_calculator_test_mob",
                MobCategory.ENEMY,
                "Damage Calculator Test Mob",
                null,
                1,
                EntityType.ZOMBIE,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                new ArrayList<>(List.of(stats)),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
        return new MobInstance(
                UUID.randomUUID(),
                template,
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
    }

    private static final class SequenceDoubleSupplier implements DoubleSupplier {

        private final double[] values;
        private int index;

        private SequenceDoubleSupplier(double... values) {
            this.values = values;
        }

        @Override
        public double getAsDouble() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
