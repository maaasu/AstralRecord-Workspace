package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * プレイヤー設定キーです。
 */
public enum PlayerSettingKey {
    DAMAGE_LOG_DISPLAY("DAMAGE_LOG_DISPLAY", "ダメージ数値表示", true),
    DAMAGE_LOG_MESSAGE("DAMAGE_LOG_MESSAGE", "ダメージ詳細メッセージ", false),
    PARTICLE_DENSITY("PARTICLE_DENSITY", "パーティクル密度", ParticleDensity.NORMAL),
    DROP_LOG_DISPLAY("DROP_LOG_DISPLAY", "レアドロップログ表示", true),
    TEMP_DROP_DISPLAY("TEMP_DROP_DISPLAY", "Temp ドロップ表示", true),
    TEMP_BLOCK_DISPLAY("TEMP_BLOCK_DISPLAY", "Temp BlockDisplay 表示", true),
    ADVENTURE_RECORD_SUPER_MODE("ADVENTURE_RECORD_SUPER_MODE", "冒険記録スーパーモード", false);

    private final String code;
    private final String displayNameJa;
    private final Object defaultValue;

    PlayerSettingKey(@NotNull String code, @NotNull String displayNameJa, @NotNull Object defaultValue) {
        this.code = code;
        this.displayNameJa = displayNameJa;
        this.defaultValue = defaultValue;
    }

    public @NotNull String getCode() {
        return code;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public @NotNull Object getDefaultValue() {
        return defaultValue;
    }

    public boolean isBooleanValue() {
        return defaultValue instanceof Boolean;
    }

    public boolean isParticleDensityValue() {
        return defaultValue instanceof ParticleDensity;
    }

    public @Nullable Object parseInputValue(@Nullable String input) {
        if (isBooleanValue()) {
            if (input == null) {
                return null;
            }
            String normalized = input.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "on", "true" -> Boolean.TRUE;
                case "off", "false" -> Boolean.FALSE;
                default -> null;
            };
        }
        if (isParticleDensityValue()) {
            return ParticleDensity.fromInput(input);
        }
        return null;
    }

    public @NotNull String formatValue(@Nullable Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? "ON" : "OFF";
        }
        if (value instanceof ParticleDensity density) {
            return density.getDisplayNameJa();
        }
        return String.valueOf(value);
    }

    public @NotNull List<String> completionValues() {
        if (isBooleanValue()) {
            return List.of("on", "off");
        }
        if (isParticleDensityValue()) {
            return ParticleDensity.completionValues();
        }
        return List.of();
    }

    public static @Nullable PlayerSettingKey fromInput(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (PlayerSettingKey key : values()) {
            if (key.code.equals(normalized)) {
                return key;
            }
        }
        return null;
    }

    public static @NotNull List<String> completionKeys() {
        return Arrays.stream(values())
            .map(key -> key.code.toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());
    }
}
