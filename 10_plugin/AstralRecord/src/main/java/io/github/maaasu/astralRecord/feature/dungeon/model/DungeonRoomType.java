package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** 部屋へ適用する視覚上の用途・装飾種別です。 */
public enum DungeonRoomType {
    STANDARD,
    SUPPORT_HALL,
    COLLAPSED,
    ORE_CHAMBER;

    /**
     * マスタ文字列を部屋種別へ変換します。
     *
     * @param raw マスタ文字列
     * @return 部屋種別
     * @throws IllegalArgumentException 未対応の種別の場合
     */
    public static @NotNull DungeonRoomType from(@NotNull String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
