package io.github.maaasu.astralRecord.feature.combat.service;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.item.model.*;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentStatContributionCalculator;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusRateCalculator;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Emits fixed inputs evaluated by the real Plugin classes; no Bukkit server is started. */
public final class CombatGoldenHarness {
    public static void main(String[] args) throws Exception {
        var victim = AstEntity.mob(DesignTestFixtures.mobInstance(100.0, 8.0, 0.0));
        var attacker = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.ATTACK, 40.0, StatusType.STRENGTH, 0.0,
                StatusType.ACCURACY, 95.0, StatusType.CRITICAL_RATE, 0.0,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0,
                StatusType.NONE_RESISTANCE_CAP, 50.0), 100.0, 100.0, 100.0));
        var context = new DamageContext(attacker, victim, 0.0, AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageScaling.ATTACKER_STATUS);
        var hit = new DamageCalculator(() -> 0.0, () -> 100.0).calculate(context);
        emit("melee_neutral", hit.finalDamage(), hit.hitChance(), hit.critical(), hit.evaded());

        var critAttacker = AstEntity.player(mock(AstPlayer.class), DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.ATTACK, 40.0, StatusType.STRENGTH, 0.0,
                StatusType.ACCURACY, 95.0, StatusType.CRITICAL_RATE, 100.0,
                StatusType.CRITICAL_DAMAGE, 150.0,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0), 100.0, 100.0, 100.0));
        var critContext = new DamageContext(critAttacker, victim, 0.0, AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageScaling.ATTACKER_STATUS);
        var crit = new DamageCalculator(() -> 0.0, () -> 0.0).calculate(critContext);
        emit("melee_critical", crit.finalDamage(), crit.hitChance(), crit.critical(), crit.evaded());

        var miss = new DamageCalculator(() -> 99.0, () -> 100.0).calculate(context);
        emit("melee_miss", miss.finalDamage(), miss.hitChance(), miss.critical(), miss.evaded());
        System.out.printf(Locale.ROOT, "{\"id\":\"timing\",\"cooldownTicks\":%d,\"attackIntervalTicks\":%d}%n",
                CombatTimingCalculator.resolveCooldownTicks(17, 50),
                CombatTimingCalculator.resolveAttackIntervalTicks(9, 175.0));
        emitTimingAndDegradation();
        emitEquipment();
        emitMobParsing();
        emitEconomyMath();
    }

    private static void emitTimingAndDegradation() {
        System.out.printf(Locale.ROOT, "{\"id\":\"cast_reduction_stacked\",\"multiplier\":%.17g,\"castTicks\":%d}%n",
                CombatTimingCalculator.resolveStackedTimeReductionMultiplier(20, 40),
                (long) Math.ceil(9 * CombatTimingCalculator.resolveStackedTimeReductionMultiplier(20, 40)));
        System.out.printf(Locale.ROOT, "{\"id\":\"cast_reduction_clamped\",\"multiplier\":%.17g,\"cooldownTicks\":%d}%n",
                CombatTimingCalculator.resolveStackedTimeReductionMultiplier(80, -10),
                CombatTimingCalculator.resolveCooldownTicks(17, 80));
        System.out.printf(Locale.ROOT, "{\"id\":\"degradation_delay\",\"beforeStage\":%d,\"firstStage\":%d,\"damageMultiplier\":%.17g,\"speedMultiplier\":%.17g}%n",
                NormalAttackDegradationService.stageForAttackCount(6, 2),
                NormalAttackDegradationService.stageForAttackCount(7, 2),
                NormalAttackDegradationService.damageMultiplierForStage(1),
                NormalAttackDegradationService.attackSpeedMultiplierForStage(1));
        System.out.printf(Locale.ROOT, "{\"id\":\"degradation_cap\",\"stage\":%d,\"damageMultiplier\":%.17g,\"speedMultiplier\":%.17g}%n",
                NormalAttackDegradationService.stageForAttackCount(100),
                NormalAttackDegradationService.damageMultiplierForStage(11),
                NormalAttackDegradationService.attackSpeedMultiplierForStage(11));
        var now = new AtomicLong(1_000);
        var degradation = new NormalAttackDegradationService(now::get);
        var player = mock(AstPlayer.class);
        var bukkit = mock(Player.class);
        when(player.getClassId()).thenReturn("swordsman");
        when(player.getBukkit()).thenReturn(bukkit);
        when(bukkit.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        for (int i = 0; i < 4; i++) degradation.beginNormalAttack(player);
        int fifthStage = degradation.beginNormalAttack(player).stage();
        now.addAndGet(10_001);
        int resetStage = degradation.beginNormalAttack(player).stage();
        System.out.printf(Locale.ROOT, "{\"id\":\"degradation_expiry\",\"fifthStage\":%d,\"afterExpiryStage\":%d}%n",
                fifthStage, resetStage);
    }

    private static void emitEquipment() {
        var enhancement = new ItemEquipmentEnhance(2, List.of(
                new ItemEquipmentEnhanceLevel(1, List.of(new ItemEquipmentEnhanceStatIncrease(
                        "ATTACK", ItemEquipmentStatType.FLAT, 2, 3)), null, 1,
                        ItemEquipmentEnhanceFailAction.NONE, null)));
        var equipment = new ItemEquipment(ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.ONE, null, 0,
                List.of(), null, List.of(), null, enhancement, null, null, List.of());
        var instance = new EquipmentInstance("i", "a", "item", 1, 1, 0, 0, 0, "", "", List.of(),
                List.of(new EquipmentEnchant("e", "i", 0, "master", "effect", "ATTACK", "SCALAR", 2),
                        new EquipmentEnchant("e2", "i", 1, "master", "effect", "ATTACK", "FLAT", 4)),
                List.of(new EquipmentRune("r", "i", 0, "rune")));
        var rune = new ItemModel(1, "rune", "rune", "ルーン", "PAPER", "common", 1, 0, null,
                null, List.of(), false, false, null, null, null,
                new ItemRune(List.of(), 0, List.of(new ItemEquipmentStat("ATTACK", ItemEquipmentStatType.FLAT, 5, 5)), List.of()),
                null, null, null);
        var result = EquipmentStatContributionCalculator.calculate(equipment, instance, id -> rune);
        var range = result.resolve(StatusType.ATTACK);
        System.out.printf(Locale.ROOT, "{\"id\":\"gear_enhance_enchant_rune\",\"min\":%.17g,\"max\":%.17g,\"scalarActive\":%b}%n",
                range.min(), range.max(), result.scalarInactiveReason(0) == EquipmentStatContributionCalculator.ScalarInactiveReason.NONE);

        var rollEquipment = new ItemEquipment(ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.ONE, null, 0,
                List.of(), null, List.of(new ItemEquipmentStat("ATTACK", ItemEquipmentStatType.FLAT, -10, 20)),
                null, null, null, null, List.of());
        var rollInstance = new EquipmentInstance("i", "a", "item", 0, 1, 0, 0, 0, "", "",
                List.of(new EquipmentStatRoll("roll", "ATTACK", "-10", "20", 0)),
                List.of(new EquipmentEnchant("e", "i", 0, "master", "effect", "ATTACK", "SCALAR", 2),
                        new EquipmentEnchant("e2", "i", 1, "master", "effect", "ATTACK", "SCALAR", 3)), List.of());
        var last = EquipmentStatContributionCalculator.calculate(rollEquipment, rollInstance, id -> null);
        var lastRange = last.resolve(StatusType.ATTACK);
        System.out.printf(Locale.ROOT, "{\"id\":\"gear_last_scalar_negative_range\",\"min\":%.17g,\"max\":%.17g,\"firstOverridden\":%b}%n",
                lastRange.min(), lastRange.max(),
                last.scalarInactiveReason(0) == EquipmentStatContributionCalculator.ScalarInactiveReason.OVERRIDDEN);
    }

    private static void emitMobParsing() throws Exception {
        String json = """
                {"schemaVersion":1,"id":"parity_mob","category":"ENEMY","name":"Level One",
                 "level":1,"entityType":"ZOMBIE",
                 "baseStats":[{"status":"MAX_HEALTH","value":10}],
                 "shield":{"enabled":true,"max":10,"rechargeTimeSeconds":15},
                 "drops":{"exp":10,"items":[{"itemId":"item:base","rate":100,"amount":"1"}]},
                 "levels":[{"level":2,"name":"Level Two",
                            "baseStats":[{"status":"MAX_HEALTH","value":20}],
                            "shield":{"max":20,"rechargeAmount":25},
                            "drops":{"exp":20}}, {"level":1}]}
                """;
        var parse = MobRepository.class.getDeclaredMethod("parseTemplate", com.google.gson.JsonObject.class);
        parse.setAccessible(true);
        var template = (MobTemplate) parse.invoke(new MobRepository(), JsonParser.parseString(json).getAsJsonObject());
        var levelTwo = template.resolveLevel(2);
        var fallback = template.resolveLevel(99);
        System.out.printf(Locale.ROOT, "{\"id\":\"mob_level_profile\",\"defaultLevel\":%d,\"levelTwoHealth\":%.17g,\"levelTwoExp\":%d,\"fallbackLevel\":%d}%n",
                template.level(), levelTwo.statValue("MAX_HEALTH", 0), levelTwo.drops().exp(), fallback.level());
        var shield = levelTwo.shield();
        System.out.printf(Locale.ROOT, "{\"id\":\"mob_shield_profile\",\"active\":%b,\"rechargeable\":%b,\"max\":%.17g,\"rechargeSeconds\":%.17g,\"rechargeAmount\":%.17g}%n",
                shield.active(), shield.rechargeable(), shield.max(), shield.rechargeTimeSeconds(), shield.resolvedRechargeAmount());
    }

    private static void emitEconomyMath() throws Exception {
        var dropRate = MobDropService.class.getDeclaredMethod("calculateEffectiveRate",
                double.class, double.class, boolean.class, double.class);
        dropRate.setAccessible(true);
        double adjustedDropRate = (double) dropRate.invoke(null, 80.0, 10.0, true, 100.0);
        double unadjustedDropRate = (double) dropRate.invoke(null, 80.0, 10.0, false, 100.0);
        System.out.printf(Locale.ROOT, "{\"id\":\"direct_drop_luck\",\"luckAffectedRate\":%.17g,\"unaffectedRate\":%.17g}%n",
                adjustedDropRate, unadjustedDropRate);

        var rate125 = DesignTestFixtures.statusSnapshot(Map.of(StatusType.EXPERIENCE_GAIN_RATE, 125.0), 20, 10, 10);
        var rate50 = DesignTestFixtures.statusSnapshot(Map.of(StatusType.EXPERIENCE_GAIN_RATE, 50.0), 20, 10, 10);
        int scaled = LevelDifferenceCalculator.scaleExperience(100, 30, 10);
        System.out.printf(Locale.ROOT, "{\"id\":\"exp_level_gap_rate\",\"levelMultiplier\":%.17g,\"scaledExp\":%d,\"afterRateExp\":%d}%n",
                LevelDifferenceCalculator.experienceMultiplier(30, 10), scaled,
                StatusRateCalculator.applyRate(rate125, StatusType.EXPERIENCE_GAIN_RATE, scaled));
        System.out.printf(Locale.ROOT, "{\"id\":\"exp_half_round\",\"baseExp\":%d,\"afterRateExp\":%d,\"neutralExp\":%d}%n",
                7, StatusRateCalculator.applyRate(rate50, StatusType.EXPERIENCE_GAIN_RATE, 7),
                StatusRateCalculator.applyRate(null, StatusType.EXPERIENCE_GAIN_RATE, 7));

        emitOrbTransition("orb_enhance_success", 1.0, ItemEquipmentEnhanceFailAction.NONE, null);
        emitOrbTransition("orb_enhance_fail_none", 0.0, ItemEquipmentEnhanceFailAction.NONE, null);
        emitOrbTransition("orb_enhance_fail_decrease", 0.0, ItemEquipmentEnhanceFailAction.DECREASE_ONE, null);
        emitOrbTransition("orb_enhance_fail_set", 0.0, ItemEquipmentEnhanceFailAction.SET_LEVEL, 1);
    }

    private static void emitOrbTransition(String id, double chance, ItemEquipmentEnhanceFailAction action,
                                          Integer failureLevel) throws Exception {
        var enhancement = new ItemEquipmentEnhance(5, List.of(new ItemEquipmentEnhanceLevel(4, List.of(),
                null, chance, action, failureLevel)));
        var equipment = new ItemEquipment(ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.ONE, null, 0,
                List.of(), null, List.of(), null, enhancement, null, null, List.of());
        var model = new ItemModel(1, "parity_weapon", "equipment", "武器", "IRON_SWORD", "common", 1, 0,
                null, null, List.of(), false, false, null, null, equipment, null, null, null, null);
        var instance = new EquipmentInstance("i", "a", "parity_weapon", 3, 0, 0, 100, 100, "", "",
                List.of(), List.of(), List.of());
        var effect = new ItemOrbEffect(ItemOrbEffectType.ENHANCE, List.of(ItemEquipmentSlot.WEAPON), null,
                ItemOrbRankMode.EXACT, null, false, null, null);
        var calculator = Class.forName("io.github.maaasu.astralRecord.feature.item.service.OrbLocalMutationCalculator");
        var apply = calculator.getDeclaredMethod("apply", ItemOrbEffect.class, ItemModel.class,
                EquipmentInstance.class, EnchantMaster.class, ItemModel.class, Integer.class);
        apply.setAccessible(true);
        var result = apply.invoke(null, effect, model, instance, null, null, null);
        var output = result.getClass().getDeclaredMethod("instance");
        output.setAccessible(true);
        var succeeded = result.getClass().getDeclaredMethod("enhancementSucceeded");
        succeeded.setAccessible(true);
        var finalInstance = (EquipmentInstance) output.invoke(result);
        System.out.printf(Locale.ROOT, "{\"id\":\"%s\",\"succeeded\":%b,\"fromLevel\":%d,\"resultLevel\":%d}%n",
                id, succeeded.invoke(result), instance.getEnhanceLevel(), finalInstance.getEnhanceLevel());
    }

    private static void emit(String id, double damage, double chance, boolean critical, boolean evaded) {
        System.out.printf(Locale.ROOT,
                "{\"id\":\"%s\",\"finalDamage\":%.17g,\"hitChance\":%.17g,\"critical\":%b,\"evaded\":%b}%n",
                id, damage, chance, critical, evaded);
    }
}
