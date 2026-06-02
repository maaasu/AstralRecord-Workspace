package io.github.maaasu.astralRecord.feature.storage.model;

import org.jetbrains.annotations.NotNull;

/**
 * ストレージ GUI の並び替えキーを表します。
 */
public enum StorageSortKey {
    STORED_ORDER("収納順"),
    ACQUIRED_ORDER("獲得順"),
    RARITY("レア度順"),
    SALE_VALUE("売値順");

    private final String displayNameJa;

    StorageSortKey(@NotNull String displayNameJa) {
        this.displayNameJa = displayNameJa;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public @NotNull StorageSortKey next() {
        StorageSortKey[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
