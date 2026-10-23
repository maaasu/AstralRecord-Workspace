package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.service.BuffService;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionTickService;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Independent fixed cases through Plugin's real status, condition and damage methods. */
public final class SkillMechanicsGoldenHarness {
    public static void main(String[] args) throws Exception {
        conditionChance();
        projectileCollision();
        conditionTicks();
        compositeRangedDamage();
        scalarBuff();
    }

    private static void conditionChance() throws Exception {
        Method chance = ConditionService.class.getDeclaredMethod("calculateApplyChance",
                double.class, double.class, double.class);
        chance.setAccessible(true);
        double adjusted = (double) chance.invoke(null, 24.5D, 20.0D, 25.0D);
        double capped = (double) chance.invoke(null, 50.0D, 150.0D, 0.0D);
        double resisted = (double) chance.invoke(null, 50.0D, 0.0D, 150.0D);
        System.out.printf(Locale.ROOT,
                "{\"id\":\"condition_chance\",\"adjusted\":%.17g,\"capped\":%.17g,\"resisted\":%.17g}%n",
                adjusted, capped, resisted);
    }

    private static void projectileCollision() throws Exception {
        Method intersection = SkillTargetingService.class.getDeclaredMethod("lineIntersectionDistance",
                BoundingBox.class, Vector.class, Vector.class, double.class, double.class);
        intersection.setAccessible(true);
        var targetBox = new BoundingBox(-.3, 1, 4.1, .3, 2, 4.7);
        var direction = new Vector(0, 0, 1);
        double standard = (double) intersection.invoke(null, targetBox, new Vector(0, 1.6, 0), direction, 16.0, .6);
        double offset = (double) intersection.invoke(null, targetBox, new Vector(0, 1.6, .8), direction, 24.0, .6);
        var projectile = new SkillProjectileSpec(16, 1.6, .6, false, 1, null, null);
        int standardTick = (int) Math.ceil(standard / projectile.speed()) - 1;
        int offsetTick = (int) Math.ceil(offset / projectile.speed()) - 1;
        System.out.printf(Locale.ROOT,
                "{\"id\":\"projectile_collision\",\"standardDistance\":%.17g,\"offsetDistance\":%.17g,\"standardTick\":%d,\"offsetTick\":%d,\"maxTicks\":%d}%n",
                standard, offset, standardTick, offsetTick,
                Math.max(1, (int) Math.ceil(projectile.range() / projectile.speed())));
    }

    private static void conditionTicks() {
        var conditions = mock(ConditionService.class);
        var damage = mock(DamageService.class);
        var sourcePlayer = mock(AstPlayer.class);
        var bukkit = mock(Player.class);
        when(sourcePlayer.getBukkit()).thenReturn(bukkit);
        when(bukkit.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        var source = AstEntity.player(sourcePlayer, DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.ATTACK, 40.0), 100, 100, 100));
        var target = AstEntity.mob(DesignTestFixtures.mobInstance(100, 0, 0));
        var active = new ActiveCondition(UUID.randomUUID(), ConditionType.BURNING, target, source,
                0, 5_000, 1_000, Long.MAX_VALUE, 1, 8, 0, 20);
        var ticks = new ConditionTickService(conditions, damage);
        ticks.tickCondition(active, 999);
        long beforeDue = active.nextTickAtMs();
        ticks.tickCondition(active, 1_000);
        long afterFirst = active.nextTickAtMs();
        ticks.tickCondition(active, 1_999);
        ticks.tickCondition(active, 2_000);
        long afterSecond = active.nextTickAtMs();
        verify(damage, times(2)).applyConditionDamage(source, target, 8, ConditionType.BURNING);
        verify(conditions, times(2)).pulse(active);
        System.out.printf(Locale.ROOT,
                "{\"id\":\"burning_ticks\",\"beforeDueMs\":%d,\"afterFirstMs\":%d,\"afterSecondMs\":%d,\"damagePerTick\":8,\"damageCalls\":2}%n",
                beforeDue, afterFirst, afterSecond);
    }

    private static void compositeRangedDamage() {
        var attacker = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.RANGED_ATTACK, 40.0, StatusType.DEXTERITY, 0.0,
                StatusType.ACCURACY, 100.0, StatusType.CRITICAL_RATE, 0.0,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0), 100, 100, 100));
        var target = AstEntity.mob(DesignTestFixtures.mobInstance(100, 8, 0));
        var components = List.of(new DamageComponent(DamageElement.FIRE, 0.6),
                new DamageComponent(DamageElement.ICE, 0.6));
        var composite = new DamageCalculator(() -> 0.0, () -> 100.0).calculate(
                new DamageContext(attacker, target, 0, AttackType.RANGED, components,
                        DamageScaling.ATTACKER_STATUS));
        var single = new DamageCalculator(() -> 0.0, () -> 100.0).calculate(
                new DamageContext(attacker, target, 0, AttackType.RANGED,
                        List.of(new DamageComponent(DamageElement.NONE, 1.2)),
                        DamageScaling.ATTACKER_STATUS));
        System.out.printf(Locale.ROOT,
                "{\"id\":\"thermal_components\",\"compositeDamage\":%.17g,\"singleDamage\":%.17g,\"hitChance\":%.17g}%n",
                composite.finalDamage(), single.finalDamage(), composite.hitChance());
    }

    private static void scalarBuff() {
        var service = new BuffService();
        var player = mock(AstPlayer.class);
        var active = new ArrayList<ActiveBuff>();
        when(player.getActiveBuffs()).thenReturn(active);
        var type = new BuffType("hunter_build_up", "BUFF", "Build Up", 200,
                false, true, "hunter_build_up",
                List.of(new BuffModifier(StatusType.RANGED_ATTACK, BuffModifierType.SCALAR, .11)));
        service.apply(player, type, 0);
        double bonus = service.getTotalBonus(player, StatusType.RANGED_ATTACK, 40);
        System.out.printf(Locale.ROOT,
                "{\"id\":\"build_up_scalar\",\"baseAttack\":40,\"modifier\":0.11,\"bonus\":%.17g,\"activeCount\":%d}%n",
                bonus, active.size());
    }
}
