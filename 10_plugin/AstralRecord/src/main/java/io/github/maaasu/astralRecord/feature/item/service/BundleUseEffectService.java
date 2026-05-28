package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bundle 使用演出のカスタム定義を filebase から解決します。
 */
public class BundleUseEffectService {

    private static final String SOUND_DIRECTORY = "10.features.item/bundle_sound";
    private static final String PARTICLE_DIRECTORY = "10.features.item/bundle_particle";

    private final FileDatabaseManager fileDatabaseManager;
    private final Map<String, BundleUseSound> soundCache = new ConcurrentHashMap<>();
    private final Map<String, BundleUseParticle> particleCache = new ConcurrentHashMap<>();

    public BundleUseEffectService() {
        this(FileDatabaseManager.getInstance());
    }

    public BundleUseEffectService(@NotNull FileDatabaseManager fileDatabaseManager) {
        this.fileDatabaseManager = fileDatabaseManager;
    }

    public @Nullable BundleUseSound findSound(@Nullable String soundId) {
        String normalizedId = normalizeId(soundId);
        if (normalizedId == null) {
            return null;
        }

        BundleUseSound cached = soundCache.get(normalizedId);
        if (cached != null) {
            return cached;
        }

        FileConfiguration config = fileDatabaseManager.getConfig(SOUND_DIRECTORY + "/v1." + normalizedId + ".yml");
        String soundKey = trimToNull(config.getString("sound"));
        if (soundKey == null) {
            return null;
        }

        BundleUseSound resolved = new BundleUseSound(
            normalizedId,
            soundKey,
            (float) config.getDouble("volume", 1.0d),
            (float) config.getDouble("pitch", 1.0d)
        );
        soundCache.put(normalizedId, resolved);
        return resolved;
    }

    public @Nullable BundleUseParticle findParticle(@Nullable String particleId) {
        String normalizedId = normalizeId(particleId);
        if (normalizedId == null) {
            return null;
        }

        BundleUseParticle cached = particleCache.get(normalizedId);
        if (cached != null) {
            return cached;
        }

        FileConfiguration config = fileDatabaseManager.getConfig(PARTICLE_DIRECTORY + "/v1." + normalizedId + ".yml");
        Particle particle = parseParticle(config.getString("particle"));
        if (particle == null) {
            return null;
        }

        BundleUseParticle resolved = new BundleUseParticle(
            normalizedId,
            particle,
            Math.max(1, config.getInt("count", 24)),
            config.getDouble("originOffsetX", 0.0d),
            config.getDouble("originOffsetY", 1.0d),
            config.getDouble("originOffsetZ", 0.0d),
            config.getDouble("offsetX", 0.4d),
            config.getDouble("offsetY", 0.5d),
            config.getDouble("offsetZ", 0.4d),
            config.getDouble("extra", 0.0d)
        );
        particleCache.put(normalizedId, resolved);
        return resolved;
    }

    private @Nullable String normalizeId(@Nullable String raw) {
        String normalized = trimToNull(raw);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private @Nullable String trimToNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private @Nullable Particle parseParticle(@Nullable String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }

        String enumName = normalized
            .replace(' ', '_')
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(enumName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record BundleUseSound(
        @NotNull String id,
        @NotNull String soundKey,
        float volume,
        float pitch
    ) {}

    public record BundleUseParticle(
        @NotNull String id,
        @NotNull Particle particle,
        int count,
        double originOffsetX,
        double originOffsetY,
        double originOffsetZ,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {}
}
