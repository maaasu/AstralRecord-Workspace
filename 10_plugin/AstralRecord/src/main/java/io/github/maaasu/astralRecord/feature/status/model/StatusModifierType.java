package io.github.maaasu.astralRecord.feature.status.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * ステータス補正値の適用種別です。
 */
public enum StatusModifierType {
    FLAT,
    SCALAR;

    /**
     * 文字列から補正種別を解決します。
     *
     * @param raw API / YAML 上の値
     * @return 解決結果。未指定や不正値は {@link #FLAT}
     */
    public static @NotNull StatusModifierType fromRaw(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return FLAT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FLAT;
        }
    }
}
