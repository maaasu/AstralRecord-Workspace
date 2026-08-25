package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * weapon equipment のタグから自動解決する通常攻撃の組み込みスキル定義です。
 */
public final class BuiltInWeaponAttackDefinitions {

    public static final String NORMAL_ATTACK_MELEE = "normal_attack_melee";
    public static final String NORMAL_ATTACK_BOW = "normal_attack_bow";
    public static final String NORMAL_ATTACK_MAGIC = "normal_attack_magic";
    private static final String IMPLEMENTATION_ID = "normal_attack";

    private BuiltInWeaponAttackDefinitions() {
    }

    public static @NotNull List<SkillDefinition> definitions() {
        return List.of(
                new SkillDefinition(
                        NORMAL_ATTACK_MELEE,
                        IMPLEMENTATION_ID,
                        ColorCodeUtil.translateAlternateColorCodes("&l通常攻撃 &r&c近接"),
                        "equipment 左クリックで発動する近接通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("particle", "SWEEP_ATTACK"),
                                Map.entry("particleCount", 1),
                                Map.entry("spreadX", 0.0D),
                                Map.entry("spreadY", 0.0D),
                                Map.entry("spreadZ", 0.0D),
                                Map.entry("extra", 0.0D),
                                Map.entry("forwardOffset", 1.1D),
                                Map.entry("upwardOffset", -0.15D),
                                Map.entry("attackType", "MELEE"),
                                Map.entry("hitRange", 2.75D),
                                Map.entry("hitRadius", 1.1D),
                                Map.entry("hitStepDistance", 0.7D),
                                Map.entry("maxTargets", 6),
                                Map.entry("sound", "entity.player.attack.sweep"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.0D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "melee"),
                        SkillKind.ACTIVE,
                        true,
                        SkillResourceType.ENERGY,
                        0.0D
                ),
                new SkillDefinition(
                        NORMAL_ATTACK_BOW,
                        IMPLEMENTATION_ID,
                        ColorCodeUtil.translateAlternateColorCodes("&l通常攻撃 &r&2間接"),
                        "equipment 左クリックで発動する弓通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("particle", "CRIT"),
                                Map.entry("particleCount", 12),
                                Map.entry("spreadX", 0.18D),
                                Map.entry("spreadY", 0.18D),
                                Map.entry("spreadZ", 0.18D),
                                Map.entry("extra", 0.02D),
                                Map.entry("forwardOffset", 1.2D),
                                Map.entry("upwardOffset", -0.1D),
                                Map.entry("attackType", "RANGED"),
                                Map.entry("hitRange", 10.5D),
                                Map.entry("hitRadius", 0.75D),
                                Map.entry("hitStepDistance", 0.9D),
                                Map.entry("maxTargets", 1),
                                Map.entry("sound", "entity.arrow.shoot"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.15D),
                                Map.entry("trailSteps", 0),
                                Map.entry("trailIntervalTicks", 1),
                                Map.entry("trailStepDistance", 0.9D),
                                Map.entry("trailParticleCount", 4),
                                Map.entry("trailSpreadX", 0.02D),
                                Map.entry("trailSpreadY", 0.02D),
                                Map.entry("trailSpreadZ", 0.02D),
                                Map.entry("trailExtra", 0.0D),
                                Map.entry("projectileSpeed", 1.35D),
                                Map.entry("projectileGravity", 0.04D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "bow"),
                        SkillKind.ACTIVE,
                        true,
                        SkillResourceType.ENERGY,
                        0.0D
                ),
                new SkillDefinition(
                        NORMAL_ATTACK_MAGIC,
                        IMPLEMENTATION_ID,
                        ColorCodeUtil.translateAlternateColorCodes("&l通常攻撃 &r&d魔法"),
                        "equipment 左クリックで発動する魔法通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("particle", "ENCHANT"),
                                Map.entry("particleCount", 10),
                                Map.entry("spreadX", 0.12D),
                                Map.entry("spreadY", 0.12D),
                                Map.entry("spreadZ", 0.12D),
                                Map.entry("extra", 0.1D),
                                Map.entry("forwardOffset", 1.0D),
                                Map.entry("upwardOffset", -0.05D),
                                Map.entry("attackType", "MAGIC"),
                                Map.entry("hitRange", 9.0D),
                                Map.entry("hitRadius", 0.85D),
                                Map.entry("hitStepDistance", 0.7D),
                                Map.entry("maxTargets", 1),
                                Map.entry("sound", "entity.evoker.cast_spell"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.2D),
                                Map.entry("trailSteps", 0),
                                Map.entry("trailIntervalTicks", 1),
                                Map.entry("trailStepDistance", 0.68D),
                                Map.entry("trailParticleCount", 4),
                                Map.entry("trailSpreadX", 0.03D),
                                Map.entry("trailSpreadY", 0.03D),
                                Map.entry("trailSpreadZ", 0.03D),
                                Map.entry("trailExtra", 0.02D),
                                Map.entry("projectileSpeed", 1.05D),
                                Map.entry("homingStrength", 0.18D),
                                Map.entry("homingRange", 4.75D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "magic"),
                        SkillKind.ACTIVE,
                        true,
                        SkillResourceType.MANA,
                        0.0D
                )
        );
    }
}
