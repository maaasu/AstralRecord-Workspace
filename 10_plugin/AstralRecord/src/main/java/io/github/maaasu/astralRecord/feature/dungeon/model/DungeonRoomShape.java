package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** 自動生成する部屋の平面形状です。 */
public enum DungeonRoomShape {
    RECTANGLE,
    CYLINDER;

    /**
     * マスタ文字列を部屋形状へ変換します。
     *
     * @param raw マスタ文字列
     * @return 部屋形状
     * @throws IllegalArgumentException 未対応の形状の場合
     */
    public static @NotNull DungeonRoomShape from(@NotNull String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
