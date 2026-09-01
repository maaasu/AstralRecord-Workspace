package io.github.maaasu.astralRecord.feature.user.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * BAN の期間種別です。
 */
public enum BanDuration {
    /** 無期限 BAN。 */
    INDEFINITE("indefinite"),
    /** 日数指定の有期限 BAN。 */
    TEMPORARY("temporary");

    private final String argument;

    BanDuration(@NotNull String argument) {
        this.argument = argument;
    }

    /**
     * コマンドで使用する引数文字列を返します。
     *
     * @return 引数文字列
     */
    public @NotNull String getArgument() {
        return argument;
    }

    /**
     * コマンド引数から期間種別を解決します。
     *
     * @param value 入力値
     * @return 解決した期間種別。不正値の場合は {@code null}
     */
    public static @Nullable BanDuration fromArgument(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BanDuration duration : values()) {
            if (duration.argument.equals(normalized)) {
                return duration;
            }
        }
        return null;
    }
}
