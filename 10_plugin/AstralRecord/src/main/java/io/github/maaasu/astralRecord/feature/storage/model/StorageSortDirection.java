package io.github.maaasu.astralRecord.feature.storage.model;

import org.jetbrains.annotations.NotNull;

/**
 * ストレージ GUI の並び方向を表します。
 */
public enum StorageSortDirection {
    ASC("昇順"),
    DESC("降順");

    private final String displayNameJa;

    StorageSortDirection(@NotNull String displayNameJa) {
        this.displayNameJa = displayNameJa;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public @NotNull StorageSortDirection next() {
        return this == ASC ? DESC : ASC;
    }
}
