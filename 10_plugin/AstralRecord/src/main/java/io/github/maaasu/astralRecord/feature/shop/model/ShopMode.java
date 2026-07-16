package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * ショップ GUI の用途を表します。
 */
public enum ShopMode {
    SHOP,
    EXCHANGE;

    /**
     * YAML 値から GUI 用途を解決します。
     *
     * @param value YAML 値
     * @return 解決した用途。未指定・不正値は {@link #SHOP}
     */
    public static @NotNull ShopMode fromValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return SHOP;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SHOP;
        }
    }
}
