package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 閲覧者が選択するダメージ数値の表示サイズです。
 */
public enum DamageNumberSize {
    SMALL("小", 0.75F),
    NORMAL("標準", 1.0F),
    LARGE("大", 1.35F);

    private final String displayNameJa;
    private final float scale;

    DamageNumberSize(String displayNameJa, float scale) {
        this.displayNameJa = displayNameJa;
        this.scale = scale;
    }

    /** @return 設定画面とコマンドで使う日本語表示名 */
    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    /** @return ダメージ数値の従来の拡大率に掛ける倍率 */
    public float getScale() {
        return scale;
    }

    /**
     * 保存値またはコマンド入力をサイズへ変換します。前後の空白と英字の大小は無視します。
     *
     * @param input サイズコード
     * @return 一致するサイズ。未指定または不明な場合はnull
     */
    public static @Nullable DamageNumberSize fromInput(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String code = input.trim().toUpperCase(Locale.ROOT);
        for (DamageNumberSize size : values()) {
            if (size.name().equals(code)) {
                return size;
            }
        }
        return null;
    }

    /** @return コマンド補完に使う小文字のサイズコード一覧 */
    public static @NotNull List<String> completionValues() {
        return Arrays.stream(values()).map(size -> size.name().toLowerCase(Locale.ROOT)).toList();
    }
}
