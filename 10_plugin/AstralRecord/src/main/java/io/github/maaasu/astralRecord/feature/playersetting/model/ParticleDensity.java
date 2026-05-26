package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * パーティクル表示密度です。
 */
public enum ParticleDensity {
    OFF("OFF", "非表示", 0.0D),
    VERY_LOW("VERY_LOW", "とても少ない", 0.25D),
    LOW("LOW", "少ない", 0.5D),
    NORMAL("NORMAL", "標準", 1.0D),
    HIGH("HIGH", "多い", 1.5D),
    VERY_HIGH("VERY_HIGH", "とても多い", 2.0D);

    private final String code;
    private final String displayNameJa;
    private final double densityScale;

    ParticleDensity(@NotNull String code, @NotNull String displayNameJa, double densityScale) {
        this.code = code;
        this.displayNameJa = displayNameJa;
        this.densityScale = densityScale;
    }

    public @NotNull String getCode() {
        return code;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public double getDensityScale() {
        return densityScale;
    }

    public static @Nullable ParticleDensity fromInput(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (ParticleDensity density : values()) {
            if (density.code.equals(normalized)) {
                return density;
            }
        }
        return null;
    }

    public static @NotNull List<String> completionValues() {
        return Arrays.stream(values())
            .map(value -> value.code.toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());
    }
}
