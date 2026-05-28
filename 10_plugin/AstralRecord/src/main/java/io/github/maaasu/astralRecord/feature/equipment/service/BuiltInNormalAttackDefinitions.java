package io.github.maaasu.astralRecord.feature.equipment.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * equipment 左クリック通常攻撃で使用する組み込みスキル定義です。
 */
public final class BuiltInNormalAttackDefinitions {

    public static final String NORMAL_ATTACK_MELEE = "normal_attack_melee";
    public static final String NORMAL_ATTACK_BOW = "normal_attack_bow";
    public static final String NORMAL_ATTACK_MAGIC = "normal_attack_magic";

    private static final String IMPLEMENTATION_ID = "normal_attack";

    private BuiltInNormalAttackDefinitions() {
    }

    public static @NotNull List<SkillDefinition> definitions() {
        return List.of(
                new SkillDefinition(
                        NORMAL_ATTACK_MELEE,
                        IMPLEMENTATION_ID,
                        "通常攻撃: 近接",
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
                                Map.entry("resourceCost", 10.0D),
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
                        "通常攻撃: 弓",
                        "equipment 左クリックで発動する弓の通常攻撃です。",
                        null,
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        0,
                        null,
                        Map.ofEntries(
                                Map.entry("resourceType", "ENERGY"),
                                Map.entry("resourceCost", 14.0D),
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
                                Map.entry("soundPitch", 1.15D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "bow")
                ),
                new SkillDefinition(
                        NORMAL_ATTACK_MAGIC,
                        IMPLEMENTATION_ID,
                        "通常攻撃: 魔法",
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
                                Map.entry("resourceCost", 8.0D),
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
                                Map.entry("soundPitch", 1.2D)
                        ),
                        List.of("builtin", "equipment", "normal_attack", "magic")
                )
        );
    }
}
