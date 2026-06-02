package io.github.maaasu.astralRecord.feature.storage.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ストレージ GUI のフィルタと並び順を保持します。
 *
 * @param rarityFilter レア度フィルタ。未指定時は全件
 * @param categoryFilter カテゴリフィルタ。未指定時は全件
 * @param sortKey 並び替えキー
 * @param sortDirection 並び方向
 */
public record StorageViewOptions(
    @Nullable String rarityFilter,
    @Nullable String categoryFilter,
    @NotNull StorageSortKey sortKey,
    @NotNull StorageSortDirection sortDirection
) {
    public static @NotNull StorageViewOptions defaults() {
        return new StorageViewOptions(null, null, StorageSortKey.STORED_ORDER, StorageSortDirection.ASC);
    }

    public @NotNull StorageViewOptions withRarityFilter(@Nullable String rarityFilter) {
        return new StorageViewOptions(rarityFilter, categoryFilter, sortKey, sortDirection);
    }

    public @NotNull StorageViewOptions withCategoryFilter(@Nullable String categoryFilter) {
        return new StorageViewOptions(rarityFilter, categoryFilter, sortKey, sortDirection);
    }

    public @NotNull StorageViewOptions withSortKey(@NotNull StorageSortKey sortKey) {
        return new StorageViewOptions(rarityFilter, categoryFilter, sortKey, sortDirection);
    }

    public @NotNull StorageViewOptions withSortDirection(@NotNull StorageSortDirection sortDirection) {
        return new StorageViewOptions(rarityFilter, categoryFilter, sortKey, sortDirection);
    }
}
