package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * ショップを開ける導線を表します。
 */
public enum ShopAccess {
    PUBLIC,
    NPC_ONLY;

    /**
     * YAML 値からアクセス種別を解決します。
     *
     * @param value YAML 値
     * @return 解決したアクセス種別。未指定・不正値は {@link #PUBLIC}
     */
    public static @NotNull ShopAccess fromValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return PUBLIC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PUBLIC;
        }
    }

    /**
     * コマンドから開けるか返します。
     *
     * @return コマンド導線を許可する場合 {@code true}
     */
    public boolean isCommandAccessible() {
        return this == PUBLIC;
    }
}
