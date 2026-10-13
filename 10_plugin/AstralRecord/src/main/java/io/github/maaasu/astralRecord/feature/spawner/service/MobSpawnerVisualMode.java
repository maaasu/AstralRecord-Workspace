package io.github.maaasu.astralRecord.feature.spawner.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 管理者に送る Mob・採集スポナー共通の表示設定です。 */
public enum MobSpawnerVisualMode {
    NORMAL("通常"),
    LIGHT("軽量"),
    OFF("非表示");

    private final String displayNameJa;

    MobSpawnerVisualMode(String displayNameJa) {
        this.displayNameJa = displayNameJa;
    }

    /**
     * @return プレイヤー向けの表示モード名
     */
    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    /**
     * @return コマンドの候補として使う小文字のモード名
     */
    public static @NotNull List<String> completionValues() {
        return Arrays.stream(values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * コマンド入力または PlayerSetting の JSON 値から表示モードを解決します。
     *
     * @param value 大文字・小文字を問わないモード名
     * @return 対応する表示モード。不明・欠落した値は null
     */
    public static @Nullable MobSpawnerVisualMode fromInput(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
