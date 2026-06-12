package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * スキル表示名の整形を共通化します。
 */
public final class SkillPresentationUtil {

    private SkillPresentationUtil() {
    }

    /**
     * スキル名をプレーンテキストへ整形します。
     *
     * @param definition スキル定義
     * @param fallback   定義未解決時の表示名
     * @return 表示用スキル名
     */
    public static @NotNull String plainName(@Nullable SkillDefinition definition, @NotNull String fallback) {
        if (definition == null) {
            return fallback;
        }

        String name = definition.getName();
        if (name == null || name.isBlank()) {
            return fallback;
        }

        String sanitized = ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(name));
        return sanitized == null || sanitized.isBlank() ? fallback : sanitized;
    }

    /**
     * スキル名を legacy color code 付きの表示用文字列へ整形します。
     *
     * @param definition スキル定義
     * @param fallback   定義未解決時の表示名
     * @return 表示用スキル名
     */
    public static @NotNull String legacyName(@Nullable SkillDefinition definition, @NotNull String fallback) {
        if (definition == null) {
            return fallback;
        }

        String name = definition.getName();
        if (name == null || name.isBlank()) {
            return fallback;
        }

        return ColorCodeUtil.translateAlternateColorCodes(name);
    }
}
