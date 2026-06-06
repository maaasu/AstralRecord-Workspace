package io.github.maaasu.astralRecord.shared.effect;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
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
    public static final SharedParticleDefinition SPAWNER_VISUAL_ENCHANT =
        new SharedParticleDefinition("spawner_visual_enchant", Particle.ENCHANT, 3, 0.35D, 0.35D, 0.35D, 0.0D);
    public static final SharedParticleDefinition ITEM_DROP_LAND_CRIT =
        new SharedParticleDefinition("item_drop_land_crit", Particle.CRIT, 10, 0.18D, 0.04D, 0.18D, 0.03D);
    public static final SharedParticleDefinition ITEM_DROP_COLLECT_END_ROD =
        new SharedParticleDefinition("item_drop_collect_end_rod", Particle.END_ROD, 8, 0.16D, 0.22D, 0.16D, 0.01D);
    public static final SharedParticleDefinition WORLD_SPAWN_RING_END_ROD =
        new SharedParticleDefinition("world_spawn_ring_end_rod", Particle.END_ROD, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    public static final SharedParticleDefinition SKILLTREE_TARGET_ENCHANT =
        new SharedParticleDefinition("skilltree_target_enchant", Particle.ENCHANT, 5, 0.18D, 0.18D, 0.18D, 0.02D);
    public static final SharedParticleDefinition SKILLTREE_TARGET_LOCKED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_locked_dust",
            Particle.DUST,
            4,
            0.05D,
            0.05D,
            0.05D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(180, 235, 255), 1.1F)
        );
    public static final SharedParticleDefinition SKILLTREE_TARGET_UNLOCKED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_unlocked_dust",
            Particle.DUST,
            5,
            0.05D,
            0.05D,
            0.05D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(255, 214, 92), 1.2F)
        );
    public static final SharedParticleDefinition SKILLTREE_TARGET_DENIED_DUST =
        new SharedParticleDefinition(
            "skilltree_target_denied_dust",
            Particle.DUST,
            4,
            0.05D,
            0.05D,
            0.05D,
            0.0D,
            new Particle.DustOptions(Color.fromRGB(255, 120, 120), 1.05F)
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

    private static final Map<String, Particle> PARTICLES = buildParticleMap();

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

    private static @NotNull Map<String, Particle> buildParticleMap() {
        Map<String, Particle> resolved = new LinkedHashMap<>();
        for (Particle particle : Particle.values()) {
            resolved.put(normalize(particle.name()), particle);
        }
        return Map.copyOf(resolved);
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
