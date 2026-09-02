package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * weapon equipment のタグから自動解決する通常攻撃の組み込みスキル定義です。
 */
public final class BuiltInWeaponAttackDefinitions {

    public static final String NORMAL_ATTACK_MELEE = "normal_attack_melee";
    public static final String NORMAL_ATTACK_HAMMER = "normal_attack_hammer";
    public static final String NORMAL_ATTACK_SPEAR = "normal_attack_spear";
    public static final String NORMAL_ATTACK_BOW = "normal_attack_bow";
    public static final String NORMAL_ATTACK_SHORTBOW = "normal_attack_shortbow";
    public static final String NORMAL_ATTACK_LONGBOW = "normal_attack_longbow";
    public static final String NORMAL_ATTACK_WAND = "normal_attack_wand";
    public static final String NORMAL_ATTACK_MAGIC = "normal_attack_magic";
    private static final String IMPLEMENTATION_ID = "normal_attack";
    private static final Set<String> NORMAL_ATTACK_IDS = Set.of(
        NORMAL_ATTACK_MELEE,
        NORMAL_ATTACK_HAMMER,
        NORMAL_ATTACK_SPEAR,
        NORMAL_ATTACK_BOW,
        NORMAL_ATTACK_SHORTBOW,
        NORMAL_ATTACK_LONGBOW,
        NORMAL_ATTACK_WAND,
        NORMAL_ATTACK_MAGIC
    );

    private BuiltInWeaponAttackDefinitions() {
    }

    /**
     * 指定したスキルIDが、装備武器の通常攻撃として扱う組み込みスキルか判定します。
     *
     * @param skillId 判定対象のスキルID
     * @return 8種類の武器通常攻撃IDのいずれかであれば true
     */
    public static boolean isNormalAttackSkillId(@NotNull String skillId) {
        return NORMAL_ATTACK_IDS.contains(skillId);
    }

    public static @NotNull List<SkillDefinition> definitions() {
        return List.of(
            definition(
                NORMAL_ATTACK_MELEE,
                "&l通常攻撃 &r&cソード",
                "ソードの近接通常攻撃です。",
                Map.<String, Object>ofEntries(
                    Map.entry("particle", "SWEEP_ATTACK"),
                    Map.entry("particleCount", 1),
                    Map.entry("secondaryCastParticle", "weapon_sword_dust"),
                    Map.entry("forwardOffset", 1.05D),
                    Map.entry("upwardOffset", -0.15D),
                    Map.entry("attackType", "MELEE"),
                    Map.entry("hitRange", 2.45D),
                    Map.entry("hitRadius", 0.85D),
                    Map.entry("hitStepDistance", 0.6D),
                    Map.entry("maxTargets", 6),
                    Map.entry("sound", "entity.player.attack.sweep"),
                    Map.entry("soundVolume", 1.0D),
                    Map.entry("soundPitch", 1.0D)
                ),
                List.of("builtin", "equipment", "normal_attack", "melee"),
                SkillResourceType.ENERGY
            ),
            definition(
                NORMAL_ATTACK_HAMMER,
                "&l通常攻撃 &r&6ハンマー",
                "ハンマーの小範囲近接通常攻撃です。",
                Map.<String, Object>ofEntries(
                    Map.entry("particle", "EXPLOSION"),
                    Map.entry("particleCount", 1),
                    Map.entry("spreadX", 0.08D),
                    Map.entry("spreadY", 0.08D),
                    Map.entry("spreadZ", 0.08D),
                    Map.entry("secondaryCastParticle", "weapon_hammer_dust"),
                    Map.entry("forwardOffset", 1.25D),
                    Map.entry("upwardOffset", -0.25D),
                    Map.entry("attackType", "MELEE"),
                    Map.entry("hitRange", 3.0D),
                    Map.entry("hitRadius", 1.2D),
                    Map.entry("hitStepDistance", 0.7D),
                    Map.entry("maxTargets", 8),
                    Map.entry("sound", "entity.generic.explode"),
                    Map.entry("soundVolume", 0.65D),
                    Map.entry("soundPitch", 1.45D)
                ),
                List.of("builtin", "equipment", "normal_attack", "melee"),
                SkillResourceType.ENERGY
            ),
            definition(
                NORMAL_ATTACK_SPEAR,
                "&l通常攻撃 &r&bスピア",
                "スピアの細長い3段近接通常攻撃です。",
                Map.<String, Object>ofEntries(
                    Map.entry("particle", "ENCHANTED_HIT"),
                    Map.entry("particleCount", 2),
                    Map.entry("secondaryCastParticle", "weapon_spear_crit"),
                    Map.entry("secondaryTrailParticle", "weapon_spear_crit"),
                    Map.entry("forwardOffset", 1.0D),
                    Map.entry("upwardOffset", -0.12D),
                    Map.entry("attackType", "MELEE"),
                    Map.entry("hitRange", 5.5D),
                    Map.entry("hitRadius", 0.45D),
                    Map.entry("hitStepDistance", 0.35D),
                    Map.entry("maxTargets", 3),
                    Map.entry("hitCount", 3),
                    Map.entry("hitIntervalTicks", 2),
                    Map.entry("damageComponents", List.of(Map.of("element", "NONE", "ratio", 0.34D))),
                    Map.entry("trailSteps", 7),
                    Map.entry("trailIntervalTicks", 1),
                    Map.entry("trailStepDistance", 0.65D),
                    Map.entry("trailParticleCount", 1),
                    Map.entry("trailSpreadX", 0.015D),
                    Map.entry("trailSpreadY", 0.015D),
                    Map.entry("trailSpreadZ", 0.015D),
                    Map.entry("sound", "item.trident.throw"),
                    Map.entry("soundVolume", 0.9D),
                    Map.entry("soundPitch", 1.2D)
                ),
                List.of("builtin", "equipment", "normal_attack", "melee"),
                SkillResourceType.ENERGY
            ),
            bowDefinition(NORMAL_ATTACK_BOW, "&l通常攻撃 &r&2ボウ", 15.75D, 12, 4, 1.15D, 1.35D, 0.7D, null),
            bowDefinition(NORMAL_ATTACK_SHORTBOW, "&l通常攻撃 &r&aショートボウ", 10.5D, 8, 2, 1.5D, 1.8D, 0.48D, null),
            bowDefinition(NORMAL_ATTACK_LONGBOW, "&l通常攻撃 &r&3ロングボウ", 23.1D, 18, 7, 0.75D, 1.0D, 1.0D, "weapon_long_bow_dust"),
            magicDefinition(NORMAL_ATTACK_WAND, "&l通常攻撃 &r&eワンド", "HORN_CORAL_BLOCK", 0.28D, 18.0D, 1.45D, "weapon_wand_dust"),
            magicDefinition(NORMAL_ATTACK_MAGIC, "&l通常攻撃 &r&dスタッフ", "TUBE_CORAL_BLOCK", 0.4D, 12.0D, 1.3D, "weapon_staff_dust")
        );
    }

    private static @NotNull SkillDefinition bowDefinition(
        @NotNull String id,
        @NotNull String name,
        double range,
        int castParticleCount,
        int trailParticleCount,
        double soundPitch,
        double projectileSpeed,
        double displayScale,
        String secondaryTrailParticle
    ) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("particle", "CRIT");
        params.put("particleCount", castParticleCount);
        params.put("spreadX", 0.18D);
        params.put("spreadY", 0.18D);
        params.put("spreadZ", 0.18D);
        params.put("extra", 0.02D);
        params.put("forwardOffset", 1.2D);
        params.put("upwardOffset", -0.1D);
        params.put("attackType", "RANGED");
        params.put("hitRange", range);
        params.put("hitRadius", 0.75D);
        params.put("maxTargets", 1);
        params.put("sound", "entity.arrow.shoot");
        params.put("soundVolume", 1.0D);
        params.put("soundPitch", soundPitch);
        params.put("trailParticleCount", trailParticleCount);
        params.put("trailSpreadX", 0.02D);
        params.put("trailSpreadY", 0.02D);
        params.put("trailSpreadZ", 0.02D);
        params.put("projectileSpeed", projectileSpeed);
        params.put("projectileGravity", id.equals(NORMAL_ATTACK_LONGBOW) ? 0.02D : 0.04D);
        params.put("displayMaterial", "ARROW");
        params.put("displayScale", displayScale);
        params.put("displayForwardOffset", 0.15D);
        params.put("displayModelPitchDegrees", 90.0D);
        params.put("displayModelYawDegrees", -45.0D);
        if (secondaryTrailParticle != null) {
            params.put("secondaryTrailParticle", secondaryTrailParticle);
        }
        return definition(
            id,
            name,
            "矢の表示を伴う間接通常攻撃です。",
            Map.copyOf(params),
            List.of("builtin", "equipment", "normal_attack", "bow"),
            SkillResourceType.ENERGY
        );
    }

    private static @NotNull SkillDefinition magicDefinition(
        @NotNull String id,
        @NotNull String name,
        @NotNull String displayMaterial,
        double displayScale,
        double displaySpinDegrees,
        double projectileSpeed,
        @NotNull String secondaryTrailParticle
    ) {
        return definition(
            id,
            name,
            "回転する魔法弾表示を伴う魔法通常攻撃です。",
            Map.<String, Object>ofEntries(
                Map.entry("particle", "ENCHANT"),
                Map.entry("particleCount", 7),
                Map.entry("spreadX", 0.06D),
                Map.entry("spreadY", 0.06D),
                Map.entry("spreadZ", 0.06D),
                Map.entry("extra", 0.04D),
                Map.entry("forwardOffset", 1.0D),
                Map.entry("upwardOffset", -0.05D),
                Map.entry("attackType", "MAGIC"),
                Map.entry("hitRange", 9.0D),
                Map.entry("hitRadius", 0.75D),
                Map.entry("maxTargets", 1),
                Map.entry("sound", "entity.evoker.cast_spell"),
                Map.entry("soundVolume", 1.0D),
                Map.entry("soundPitch", 1.45D),
                Map.entry("trailParticleCount", id.equals(NORMAL_ATTACK_WAND) ? 2 : 3),
                Map.entry("trailSpreadX", 0.02D),
                Map.entry("trailSpreadY", 0.02D),
                Map.entry("trailSpreadZ", 0.02D),
                Map.entry("trailExtra", 0.01D),
                Map.entry("projectileSpeed", projectileSpeed),
                Map.entry("homingStrength", 0.18D),
                Map.entry("homingRange", 4.75D),
                Map.entry("secondaryTrailParticle", secondaryTrailParticle),
                Map.entry("displayMaterial", displayMaterial),
                Map.entry("displayScale", displayScale),
                Map.entry("displaySpinDegrees", displaySpinDegrees)
            ),
            List.of("builtin", "equipment", "normal_attack", "magic"),
            SkillResourceType.MANA
        );
    }

    private static @NotNull SkillDefinition definition(
        @NotNull String id,
        @NotNull String name,
        @NotNull String description,
        @NotNull Map<String, Object> params,
        @NotNull List<String> tags,
        @NotNull SkillResourceType resourceType
    ) {
        return new SkillDefinition(
            id,
            IMPLEMENTATION_ID,
            ColorCodeUtil.translateAlternateColorCodes(name),
            description,
            null,
            List.of(),
            0L,
            0.0D,
            0L,
            0,
            null,
            params,
            tags,
            SkillKind.ACTIVE,
            true,
            resourceType,
            0.0D
        );
    }
}
