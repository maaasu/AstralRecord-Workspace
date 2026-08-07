package io.github.maaasu.astralRecord.shared.effect;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * プラグイン共通のパーティクル定義と解決処理を提供します。
 */
public final class SharedParticleDefinitions {

    public static final SharedParticleDefinition DODGE_CLOUD =
        new SharedParticleDefinition("dodge_cloud", Particle.CLOUD, 6, 0.2D, 0.05D, 0.2D, 0.0D);
    public static final SharedParticleDefinition AIR_ACTION_CLOUD =
        new SharedParticleDefinition("air_action_cloud", Particle.CLOUD, 8, 0.18D, 0.05D, 0.18D, 0.0D);
    public static final SharedParticleDefinition FIRE_BOOST_FLAME =
        new SharedParticleDefinition("fire_boost_flame", Particle.FLAME, 40, 0.4D, 0.6D, 0.4D, 0.03D);
    public static final SharedParticleDefinition FIRE_BOOST_LAVA =
        new SharedParticleDefinition("fire_boost_lava", Particle.LAVA, 8, 0.2D, 0.4D, 0.2D, 0.01D);
    public static final SharedParticleDefinition MOB_DEATH_POOF =
        new SharedParticleDefinition("mob_death_poof", Particle.POOF, 28, 0.45D, 0.35D, 0.45D, 0.02D);
    public static final SharedParticleDefinition MOB_DEATH_CRIT =
        new SharedParticleDefinition("mob_death_crit", Particle.CRIT, 18, 0.35D, 0.3D, 0.35D, 0.1D);
    public static final SharedParticleDefinition SHIELD_HIT_DUST =
        new SharedParticleDefinition(
            "shield_hit_dust",
            Particle.DUST,
            2,
            0.02D,
            0.02D,
            0.02D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(80, 190, 255), 1.2F)
        );
    public static final SharedParticleDefinition SHIELD_BREAK_DUST =
        new SharedParticleDefinition(
            "shield_break_dust",
            Particle.DUST,
            3,
            0.03D,
            0.03D,
            0.03D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(150, 235, 255), 1.45F)
        );
    public static final SharedParticleDefinition DAMAGE_HIT_INDICATOR =
        new SharedParticleDefinition("damage_hit_indicator", Particle.DAMAGE_INDICATOR, 6, 0.18D, 0.25D, 0.18D, 0.0D);
    public static final SharedParticleDefinition CRITICAL_HIT_CRIT =
        new SharedParticleDefinition("critical_hit_crit", Particle.CRIT, 18, 0.34D, 0.38D, 0.34D, 0.16D);
    public static final SharedParticleDefinition SUPER_STAR_CRITICAL_BURST_END_ROD =
        new SharedParticleDefinition("super_star_critical_burst_end_rod", Particle.END_ROD, 24, 0.42D, 0.48D, 0.42D, 0.08D);
    public static final SharedParticleDefinition SUPER_STAR_CRITICAL_TRAIL_END_ROD =
        new SharedParticleDefinition("super_star_critical_trail_end_rod", Particle.END_ROD, 1, 0.03D, 0.03D, 0.03D, 0.0D);
    public static final SharedParticleDefinition SUPER_STAR_CRITICAL_TRAIL_SPARK =
        new SharedParticleDefinition("super_star_critical_trail_spark", Particle.ELECTRIC_SPARK, 1, 0.04D, 0.04D, 0.04D, 0.0D);
    public static final SharedParticleDefinition SUPER_STAR_CRITICAL_IMPACT =
        new SharedParticleDefinition(
            "super_star_critical_impact",
            Particle.FLASH,
            1,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            Color.WHITE
        );
    public static final SharedParticleDefinition SPAWNER_VISUAL_ENCHANT =
        new SharedParticleDefinition("spawner_visual_enchant", Particle.ENCHANT, 3, 0.35D, 0.35D, 0.35D, 0.0D);
    public static final SharedParticleDefinition NPC_BLOCK_AMBIENT_ENCHANT =
        new SharedParticleDefinition("npc_block_ambient_enchant", Particle.ENCHANT, 5, 0.45D, 0.45D, 0.45D, 0.0D);
    public static final SharedParticleDefinition ITEM_DROP_LAND_CRIT =
        new SharedParticleDefinition("item_drop_land_crit", Particle.CRIT, 10, 0.18D, 0.04D, 0.18D, 0.03D);
    public static final SharedParticleDefinition ITEM_DROP_COLLECT_END_ROD =
        new SharedParticleDefinition("item_drop_collect_end_rod", Particle.END_ROD, 8, 0.16D, 0.22D, 0.16D, 0.01D);
    public static final SharedParticleDefinition BUNDLE_USE_DEFAULT =
        new SharedParticleDefinition("bundle_use_default", Particle.TOTEM_OF_UNDYING, 24, 0.4D, 0.5D, 0.4D, 0.0D);
    public static final SharedParticleDefinition WORLD_SPAWN_RING_END_ROD =
        new SharedParticleDefinition("world_spawn_ring_end_rod", Particle.END_ROD, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition BOSS_ENTRY_RING_DUST =
        new SharedParticleDefinition(
            "boss_entry_ring_dust",
            Particle.DUST,
            1,
            0.01D,
            0.01D,
            0.01D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(255, 82, 146), 1.05F)
        );
    public static final SharedParticleDefinition BOSS_ENTRY_SOUL_FIRE =
        new SharedParticleDefinition("boss_entry_soul_fire", Particle.SOUL_FIRE_FLAME, 3, 0.16D, 0.12D, 0.16D, 0.01D);
    public static final SharedParticleDefinition BASE_RETURN_RING_END_ROD =
        new SharedParticleDefinition("base_return_ring_end_rod", Particle.END_ROD, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition BASE_RETURN_PORTAL =
        new SharedParticleDefinition("base_return_portal", Particle.PORTAL, 10, 0.30D, 0.45D, 0.30D, 0.10D);
    public static final SharedParticleDefinition POTION_USE_RING_DUST =
        new SharedParticleDefinition(
            "potion_use_ring_dust",
            Particle.DUST,
            1,
            0.01D,
            0.01D,
            0.01D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(120, 255, 190), 0.85F)
        );
    public static final SharedParticleDefinition POTION_USE_ENCHANT =
        new SharedParticleDefinition("potion_use_enchant", Particle.ENCHANT, 3, 0.24D, 0.28D, 0.24D, 0.02D);
    public static final SharedParticleDefinition EQUIPMENT_REPAIR_ENCHANT =
        new SharedParticleDefinition("equipment_repair_enchant", Particle.ENCHANT, 24, 0.30D, 0.35D, 0.30D, 0.0D);
    public static final SharedParticleDefinition EQUIPMENT_ENHANCEMENT_SUCCESS =
        new SharedParticleDefinition("equipment_enhancement_success", Particle.ENCHANT, 30, 0.35D, 0.45D, 0.35D, 0.0D);
    public static final SharedParticleDefinition EQUIPMENT_ENHANCEMENT_FAILURE =
        new SharedParticleDefinition("equipment_enhancement_failure", Particle.SMOKE, 18, 0.25D, 0.35D, 0.25D, 0.02D);
    public static final SharedParticleDefinition EQUIPMENT_ENHANCEMENT_DESTROY =
        new SharedParticleDefinition("equipment_enhancement_destroy", Particle.LARGE_SMOKE, 22, 0.30D, 0.35D, 0.30D, 0.02D);
    public static final SharedParticleDefinition SKILLTREE_TARGET_ENCHANT =
        new SharedParticleDefinition("skilltree_target_enchant", Particle.ENCHANT, 2, 0.10D, 0.10D, 0.10D, 0.01D);
    public static final SharedParticleDefinition SKILLTREE_TARGET_LOCKED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_locked_dust",
            Particle.DUST,
            2,
            0.03D,
            0.03D,
            0.03D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(180, 235, 255), 0.7F)
        );
    public static final SharedParticleDefinition SKILLTREE_TARGET_UNLOCKED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_unlocked_dust",
            Particle.DUST,
            2,
            0.03D,
            0.03D,
            0.03D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(255, 214, 92), 0.78F)
        );
    public static final SharedParticleDefinition SKILLTREE_TARGET_DENIED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_denied_dust",
            Particle.DUST,
            2,
            0.03D,
            0.03D,
            0.03D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(255, 120, 120), 0.68F)
        );
    public static final SharedParticleDefinition MAGIC_PROJECTILE_CORE_DUST =
        new SharedParticleDefinition(
            "magic_projectile_core_dust",
            Particle.DUST,
            3,
            0.03D,
            0.03D,
            0.03D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(170, 70, 255), 1.2F)
        );
    public static final SharedParticleDefinition MAGIC_IMPACT_ENCHANT =
        new SharedParticleDefinition("magic_impact_enchant", Particle.ENCHANT, 8, 0.12D, 0.12D, 0.12D, 0.05D);
    public static final SharedParticleDefinition MAGIC_IMPACT_DUST =
        new SharedParticleDefinition(
            "magic_impact_dust",
            Particle.DUST,
            8,
            0.08D,
            0.08D,
            0.08D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(170, 70, 255), 1.2F)
        );
    public static final SharedParticleDefinition WEAPON_ATTACK_DEFAULT =
        new SharedParticleDefinition("weapon_attack_default", Particle.CRIT, 10, 0.15D, 0.15D, 0.15D, 0.0D);
    public static final SharedParticleDefinition WEAPON_MAGIC_PROJECTILE_DEFAULT =
        new SharedParticleDefinition("weapon_magic_projectile_default", Particle.ENCHANT, 10, 0.05D, 0.05D, 0.05D, 0.0D);
    public static final SharedParticleDefinition SKILL_SWORD_SWEEP =
        new SharedParticleDefinition("skill_sword_sweep", Particle.SWEEP_ATTACK, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition SKILL_SWORD_EDGE =
        new SharedParticleDefinition("skill_sword_edge", Particle.CRIT, 2, 0.04D, 0.04D, 0.04D, 0.02D);
    public static final SharedParticleDefinition ADVENTURER_ASTRAL_EDGE_CRIT =
        new SharedParticleDefinition("adventurer_astral_edge_crit", Particle.CRIT, 3, 0.04D, 0.04D, 0.04D, 0.08D);
    public static final SharedParticleDefinition ADVENTURER_ASTRAL_EDGE_SPARK =
        new SharedParticleDefinition("adventurer_astral_edge_spark", Particle.ELECTRIC_SPARK, 2, 0.03D, 0.03D, 0.03D, 0.04D);
    public static final SharedParticleDefinition ADVENTURER_SMASH_CRIT =
        new SharedParticleDefinition("adventurer_smash_crit", Particle.CRIT, 5, 0.08D, 0.12D, 0.08D, 0.12D);
    public static final SharedParticleDefinition ADVENTURER_SMASH_SPARK =
        new SharedParticleDefinition("adventurer_smash_spark", Particle.ELECTRIC_SPARK, 3, 0.06D, 0.08D, 0.06D, 0.08D);
    public static final SharedParticleDefinition ADVENTURER_SMASH_SWEEP =
        new SharedParticleDefinition("adventurer_smash_sweep", Particle.SWEEP_ATTACK, 2, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition ADVENTURER_MEDITATION_CHARGE =
        new SharedParticleDefinition("adventurer_meditation_charge", Particle.ENCHANT, 2, 0.10D, 0.14D, 0.10D, 0.01D);
    public static final SharedParticleDefinition ADVENTURER_MEDITATION_RING =
        new SharedParticleDefinition(
            "adventurer_meditation_ring",
            Particle.DUST,
            1,
            0.01D,
            0.01D,
            0.01D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(150, 220, 255), 0.9F)
        );
    public static final SharedParticleDefinition ADVENTURER_MEDITATION_AURA =
        new SharedParticleDefinition("adventurer_meditation_aura", Particle.ENCHANT, 1, 0.02D, 0.02D, 0.02D, 0.01D);
    public static final SharedParticleDefinition ADVENTURER_BLAST_ARROW_TRAIL =
        new SharedParticleDefinition("adventurer_blast_arrow_trail", Particle.CRIT, 1, 0.02D, 0.02D, 0.02D, 0.015D);
    public static final SharedParticleDefinition ADVENTURER_BLAST_ARROW_IMPACT =
        new SharedParticleDefinition("adventurer_blast_arrow_impact", Particle.ELECTRIC_SPARK, 10, 0.12D, 0.12D, 0.12D, 0.08D);
    public static final SharedParticleDefinition ADVENTURER_BLAST_ARROW_SHOCKWAVE =
        new SharedParticleDefinition("adventurer_blast_arrow_shockwave", Particle.CLOUD, 2, 0.03D, 0.03D, 0.03D, 0.01D);
    public static final SharedParticleDefinition SKILL_SWORD_GUARD_DUST =
        new SharedParticleDefinition(
            "skill_sword_guard_dust",
            Particle.DUST,
            1,
            0.02D,
            0.02D,
            0.02D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(105, 175, 230), 1.0F)
        );
    public static final SharedParticleDefinition SKILL_HUNTER_ARROW =
        new SharedParticleDefinition("skill_hunter_arrow", Particle.CRIT, 1, 0.02D, 0.02D, 0.02D, 0.01D);
    public static final SharedParticleDefinition SKILL_HUNTER_IMPACT =
        new SharedParticleDefinition("skill_hunter_impact", Particle.END_ROD, 3, 0.08D, 0.08D, 0.08D, 0.01D);
    public static final SharedParticleDefinition SKILL_HUNTER_TRAP_DUST =
        new SharedParticleDefinition(
            "skill_hunter_trap_dust",
            Particle.DUST,
            1,
            0.02D,
            0.02D,
            0.02D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(120, 175, 80), 0.9F)
        );
    public static final SharedParticleDefinition SKILL_MAGE_FIRE =
        new SharedParticleDefinition("skill_mage_fire", Particle.FLAME, 2, 0.06D, 0.06D, 0.06D, 0.01D);
    public static final SharedParticleDefinition SKILL_MAGE_ICE =
        new SharedParticleDefinition("skill_mage_ice", Particle.SNOWFLAKE, 2, 0.06D, 0.06D, 0.06D, 0.01D);
    public static final SharedParticleDefinition SKILL_MAGE_LIGHTNING =
        new SharedParticleDefinition("skill_mage_lightning", Particle.ELECTRIC_SPARK, 2, 0.06D, 0.06D, 0.06D, 0.02D);
    public static final SharedParticleDefinition SKILL_MAGE_ARCANE_DUST =
        new SharedParticleDefinition(
            "skill_mage_arcane_dust",
            Particle.DUST,
            1,
            0.02D,
            0.02D,
            0.02D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(175, 95, 245), 1.0F)
        );
    public static final SharedParticleDefinition SKILL_MAGE_PORTAL =
        new SharedParticleDefinition("skill_mage_portal", Particle.PORTAL, 5, 0.14D, 0.22D, 0.14D, 0.05D);
    public static final SharedParticleDefinition CLASS_LEVEL_UP_DUST =
        new SharedParticleDefinition(
            "class_level_up_dust",
            Particle.DUST,
            28,
            0.45D,
            0.85D,
            0.45D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(80, 220, 255), 1.25F)
        );
    public static final SharedParticleDefinition CLASS_LEVEL_UP_ENCHANT =
        new SharedParticleDefinition("class_level_up_enchant", Particle.ENCHANT, 24, 0.55D, 0.95D, 0.55D, 0.06D);
    public static final SharedParticleDefinition PLAYER_LEVEL_UP_TOTEM =
        new SharedParticleDefinition("player_level_up_totem", Particle.TOTEM_OF_UNDYING, 54, 0.7D, 1.1D, 0.7D, 0.08D);
    public static final SharedParticleDefinition PLAYER_LEVEL_UP_END_ROD =
        new SharedParticleDefinition("player_level_up_end_rod", Particle.END_ROD, 36, 0.55D, 1.0D, 0.55D, 0.04D);
    public static final SharedParticleDefinition TELEPORTER_UNLOCK_RING_END_ROD =
        new SharedParticleDefinition("teleporter_unlock_ring_end_rod", Particle.END_ROD, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition TELEPORTER_UNLOCK_ENCHANT =
        new SharedParticleDefinition("teleporter_unlock_enchant", Particle.ENCHANT, 18, 0.34D, 0.42D, 0.34D, 0.04D);
    public static final SharedParticleDefinition TELEPORTER_UNLOCK_DUST =
        new SharedParticleDefinition(
            "teleporter_unlock_dust",
            Particle.DUST,
            14,
            0.24D,
            0.32D,
            0.24D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(110, 240, 255), 0.95F)
        );
    public static final SharedParticleDefinition CONDITION_BURNING_FLAME =
        new SharedParticleDefinition("condition_burning_flame", Particle.FLAME, 4, 0.22D, 0.32D, 0.22D, 0.01D);
    public static final SharedParticleDefinition CONDITION_POISON_DUST =
        new SharedParticleDefinition(
            "condition_poison_dust",
            Particle.DUST,
            4,
            0.18D,
            0.22D,
            0.18D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(80, 210, 70), 0.9F)
        );
    public static final SharedParticleDefinition CONDITION_ICE_DUST =
        new SharedParticleDefinition(
            "condition_ice_dust",
            Particle.DUST,
            4,
            0.20D,
            0.24D,
            0.20D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(125, 220, 255), 0.9F)
        );
    public static final SharedParticleDefinition CONDITION_SHOCKED_SPARK =
        new SharedParticleDefinition("condition_shocked_spark", Particle.ELECTRIC_SPARK, 5, 0.24D, 0.34D, 0.24D, 0.025D);
    public static final SharedParticleDefinition CONDITION_WEAKNESS_DUST =
        new SharedParticleDefinition(
            "condition_weakness_dust",
            Particle.DUST,
            4,
            0.20D,
            0.24D,
            0.20D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(110, 70, 130), 0.9F)
        );
    public static final SharedParticleDefinition CONDITION_HEALING_INHIBITION_DUST =
        new SharedParticleDefinition(
            "condition_healing_inhibition_dust",
            Particle.DUST,
            4,
            0.22D,
            0.26D,
            0.22D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(175, 30, 65), 0.95F)
        );
    private static final Map<String, Particle> PARTICLES = buildParticleMap();
    private static final Map<String, SharedParticleDefinition> DEFINITIONS = buildDefinitionMap();

    private SharedParticleDefinitions() {}

    /**
     * 共通の正規化ルールでパーティクル種別を解決します。
     *
     * @param raw 入力文字列
     * @return 解決できたパーティクル。無効な場合は {@code null}
     */
    public static @Nullable Particle resolveParticle(@Nullable String raw) {
        String normalized = normalize(raw);
        return normalized == null ? null : PARTICLES.get(normalized);
    }

    /**
     * パーティクル名として有効かを判定します。
     *
     * @param raw 入力文字列
     * @return 有効な場合は {@code true}
     */
    public static boolean isSupportedParticle(@Nullable String raw) {
        return resolveParticle(raw) != null;
    }

    /**
     * パーティクル ID を、追加データを含む共通表示定義へ解決します。
     * <p>
     * 共有カスタム定義を優先し、追加データが不要な Bukkit パーティクルだけを
     * 既定の単一点表示として解決します。DUST など追加データを要求する種類は、
     * 色やサイズを持つ共有カスタム定義の ID で指定してください。
     *
     * @param raw パーティクル ID
     * @return 解決できた表示定義。無効または追加データ不足の場合は {@code null}
     */
    public static @Nullable SharedParticleDefinition resolveDefinition(@Nullable String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }

        SharedParticleDefinition definition = DEFINITIONS.get(normalized);
        if (definition != null) {
            return definition;
        }

        Particle particle = PARTICLES.get(normalized);
        if (particle == null || particle.getDataType() != Void.class) {
            return null;
        }
        return new SharedParticleDefinition(normalized, particle, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    /**
     * コマンド入力に利用できるパーティクル ID の一覧を返します。
     *
     * @return 共有カスタム定義と追加データ不要な Bukkit パーティクルのソート済み ID
     */
    public static @NotNull List<String> getDefinitionIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        DEFINITIONS.keySet().forEach(id -> ids.put(id, id));
        PARTICLES.forEach((id, particle) -> {
            if (particle.getDataType() == Void.class) {
                ids.put(id, id);
            }
        });
        return ids.keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static @NotNull Map<String, Particle> buildParticleMap() {
        Map<String, Particle> resolved = new LinkedHashMap<>();
        for (Particle particle : Particle.values()) {
            resolved.put(normalize(particle.name()), particle);
        }
        return Map.copyOf(resolved);
    }

    private static @NotNull Map<String, SharedParticleDefinition> buildDefinitionMap() {
        Map<String, SharedParticleDefinition> definitions = new LinkedHashMap<>();
        try {
            for (Field field : SharedParticleDefinitions.class.getFields()) {
                if (field.getType() != SharedParticleDefinition.class) {
                    continue;
                }
                SharedParticleDefinition definition = (SharedParticleDefinition) field.get(null);
                definitions.put(normalize(definition.id()), definition);
            }
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        return Collections.unmodifiableMap(definitions);
    }

    private static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
            .replace(' ', '_')
            .replace('-', '_')
            .toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
