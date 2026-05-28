package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * weapon equipment の通常攻撃・特殊攻撃で使用する組み込みスキル定義です。
 */
public final class BuiltInWeaponAttackDefinitions {

    public static final String NORMAL_ATTACK_MELEE = "normal_attack_melee";
    public static final String NORMAL_ATTACK_BOW = "normal_attack_bow";
    public static final String NORMAL_ATTACK_MAGIC = "normal_attack_magic";
    public static final String SPECIAL_ATTACK_MELEE = "special_attack_melee";
    public static final String SPECIAL_ATTACK_BOW = "special_attack_bow";
    public static final String SPECIAL_ATTACK_MAGIC = "special_attack_magic";

    private static final String IMPLEMENTATION_ID = "normal_attack";

    private BuiltInWeaponAttackDefinitions() {
    }

    public static @NotNull List<SkillDefinition> definitions() {
        return List.of(
                new SkillDefinition(
                        NORMAL_ATTACK_MELEE,
                        IMPLEMENTATION_ID,
                        "通常攻撃 近接",
                        "equipment 左クリックで発動する近接通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "ENERGY"),
                                Map.entry("resourceCost", 0.0D),
                                Map.entry("particle", "SWEEP_ATTACK"),
                                Map.entry("particleCount", 1),
                                Map.entry("spreadX", 0.0D),
                                Map.entry("spreadY", 0.0D),
                                Map.entry("spreadZ", 0.0D),
                                Map.entry("extra", 0.0D),
                                Map.entry("forwardOffset", 1.1D),
                                Map.entry("upwardOffset", -0.15D),
                                Map.entry("sound", "entity.player.attack.sweep"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.0D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "melee")
                ),
                new SkillDefinition(
                        NORMAL_ATTACK_BOW,
                        IMPLEMENTATION_ID,
                        "通常攻撃 弓",
                        "equipment 左クリックで発動する弓通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "ENERGY"),
                                Map.entry("resourceCost", 0.0D),
                                Map.entry("particle", "CRIT"),
                                Map.entry("particleCount", 12),
                                Map.entry("spreadX", 0.18D),
                                Map.entry("spreadY", 0.18D),
                                Map.entry("spreadZ", 0.18D),
                                Map.entry("extra", 0.02D),
                                Map.entry("forwardOffset", 1.2D),
                                Map.entry("upwardOffset", -0.1D),
                                Map.entry("sound", "entity.arrow.shoot"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.15D),
                                Map.entry("trailSteps", 7),
                                Map.entry("trailIntervalTicks", 1),
                                Map.entry("trailStepDistance", 0.9D),
                                Map.entry("trailParticleCount", 6),
                                Map.entry("trailSpreadX", 0.04D),
                                Map.entry("trailSpreadY", 0.04D),
                                Map.entry("trailSpreadZ", 0.04D),
                                Map.entry("trailExtra", 0.0D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "bow")
                ),
                new SkillDefinition(
                        NORMAL_ATTACK_MAGIC,
                        IMPLEMENTATION_ID,
                        "通常攻撃 魔法",
                        "equipment 左クリックで発動する魔法通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "MANA"),
                                Map.entry("resourceCost", 0.0D),
                                Map.entry("particle", "ENCHANT"),
                                Map.entry("particleCount", 20),
                                Map.entry("spreadX", 0.25D),
                                Map.entry("spreadY", 0.25D),
                                Map.entry("spreadZ", 0.25D),
                                Map.entry("extra", 0.35D),
                                Map.entry("forwardOffset", 1.0D),
                                Map.entry("upwardOffset", -0.05D),
                                Map.entry("sound", "entity.evoker.cast_spell"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 1.2D),
                                Map.entry("trailSteps", 8),
                                Map.entry("trailIntervalTicks", 1),
                                Map.entry("trailStepDistance", 0.68D),
                                Map.entry("trailParticleCount", 8),
                                Map.entry("trailSpreadX", 0.06D),
                                Map.entry("trailSpreadY", 0.06D),
                                Map.entry("trailSpreadZ", 0.06D),
                                Map.entry("trailExtra", 0.15D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "magic")
                ),
                new SkillDefinition(
                        SPECIAL_ATTACK_MELEE,
                        IMPLEMENTATION_ID,
                        "特殊攻撃 近接",
                        "equipment 右クリックで発動する近接特殊攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "ENERGY"),
                                Map.entry("resourceCost", 24.0D),
                                Map.entry("particle", "SWEEP_ATTACK"),
                                Map.entry("particleCount", 8),
                                Map.entry("spreadX", 0.35D),
                                Map.entry("spreadY", 0.2D),
                                Map.entry("spreadZ", 0.35D),
                                Map.entry("extra", 0.0D),
                                Map.entry("forwardOffset", 1.25D),
                                Map.entry("upwardOffset", -0.1D),
                                Map.entry("sound", "entity.player.attack.strong"),
                                Map.entry("soundVolume", 1.0D),
                                Map.entry("soundPitch", 0.95D)
                        ),
                        List.of("builtin", "equipment", "special_attack", "melee")
                ),
                new SkillDefinition(
                        SPECIAL_ATTACK_BOW,
                        IMPLEMENTATION_ID,
                        "特殊攻撃 弓",
                        "equipment 右クリックで発動する弓特殊攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "ENERGY"),
                                Map.entry("resourceCost", 30.0D),
                                Map.entry("particle", "CRIT"),
                                Map.entry("particleCount", 24),
                                Map.entry("spreadX", 0.3D),
                                Map.entry("spreadY", 0.3D),
                                Map.entry("spreadZ", 0.3D),
                                Map.entry("extra", 0.08D),
                                Map.entry("forwardOffset", 1.35D),
                                Map.entry("upwardOffset", -0.08D),
                                Map.entry("sound", "entity.arrow.shoot"),
                                Map.entry("soundVolume", 1.15D),
                                Map.entry("soundPitch", 0.85D),
                                Map.entry("projectileType", "arrow"),
                                Map.entry("projectileSpeed", 3.0D)
                        ),
                        List.of("builtin", "equipment", "special_attack", "bow")
                ),
                new SkillDefinition(
                        SPECIAL_ATTACK_MAGIC,
                        IMPLEMENTATION_ID,
                        "特殊攻撃 魔法",
                        "equipment 右クリックで発動する魔法特殊攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "MANA"),
                                Map.entry("resourceCost", 18.0D),
                                Map.entry("particle", "ENCHANT"),
                                Map.entry("particleCount", 36),
                                Map.entry("spreadX", 0.4D),
                                Map.entry("spreadY", 0.4D),
                                Map.entry("spreadZ", 0.4D),
                                Map.entry("extra", 0.5D),
                                Map.entry("forwardOffset", 1.1D),
                                Map.entry("upwardOffset", 0.0D),
                                Map.entry("sound", "entity.evoker.cast_spell"),
                                Map.entry("soundVolume", 1.1D),
                                Map.entry("soundPitch", 0.9D),
                                Map.entry("projectileType", "magic"),
                                Map.entry("projectileSpeed", 2.0D)
                        ),
                        List.of("builtin", "equipment", "special_attack", "magic")
                )
        );
    }
}
