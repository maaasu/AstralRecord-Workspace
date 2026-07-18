package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemBundleParticle;
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleSound;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * bundle 使用演出の inline 定義を解決し、未指定時は既定値を返します。
 */
public class BundleUseEffectService {

    public static final BundleUseSound DEFAULT_SOUND =
        new BundleUseSound("default", "block.chest.open", 0.6f, 1.28f);
    public static final BundleUseParticle DEFAULT_PARTICLE =
        new BundleUseParticle("default", Particle.TOTEM_OF_UNDYING, 24, 0.0d, 1.0d, 0.0d, 0.4d, 0.5d, 0.4d, 0.0d);

    public BundleUseEffectService() {}

    public @NotNull BundleUseSound findSound(@Nullable ItemBundleSound definition) {
        if (definition == null) {
            return DEFAULT_SOUND;
        }
        if (trimToNull(definition.getSound()) != null) {
            return new BundleUseSound(
                "inline",
                definition.getSound().trim(),
                (float) (definition.getVolume() == null ? 1.0d : definition.getVolume()),
                (float) (definition.getPitch() == null ? 1.0d : definition.getPitch())
            );
        }
        return DEFAULT_SOUND;
    }

    public @NotNull BundleUseParticle findParticle(@Nullable ItemBundleParticle definition) {
        if (definition == null) {
            return DEFAULT_PARTICLE;
        }
        Particle particle = parseParticle(definition.getParticle());
        if (particle != null) {
            return new BundleUseParticle(
                "inline",
                particle,
                Math.max(1, definition.getCount() == null ? 24 : definition.getCount()),
                valueOrDefault(definition.getOriginOffsetX(), 0.0d),
                valueOrDefault(definition.getOriginOffsetY(), 1.0d),
                valueOrDefault(definition.getOriginOffsetZ(), 0.0d),
                valueOrDefault(definition.getOffsetX(), 0.4d),
                valueOrDefault(definition.getOffsetY(), 0.5d),
                valueOrDefault(definition.getOffsetZ(), 0.4d),
                valueOrDefault(definition.getExtra(), 0.0d)
            );
        }
        return DEFAULT_PARTICLE;
    }

    private @Nullable String trimToNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private @Nullable Particle parseParticle(@Nullable String raw) {
        return SharedParticleDefinitions.resolveParticle(raw);
    }

    private double valueOrDefault(@Nullable Double value, double defaultValue) {
        return value == null ? defaultValue : value;
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
