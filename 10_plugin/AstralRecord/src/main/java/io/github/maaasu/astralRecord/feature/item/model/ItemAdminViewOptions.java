package io.github.maaasu.astralRecord.feature.item.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 管理者用アイテム GUI のフィルタ状態を保持します。
 *
 * @param categoryFilter カテゴリフィルタ。未指定時は全件表示
 * @param rarityFilter レア度フィルタ。未指定時は全件表示
 */
public record ItemAdminViewOptions(
    @Nullable String categoryFilter,
    @Nullable String rarityFilter
) {
    /**
     * デフォルト状態のフィルタを返します。
     *
     * @return フィルタ未指定の初期状態
     */
    public static @NotNull ItemAdminViewOptions defaults() {
        return new ItemAdminViewOptions(null, null);
    }

    /**
     * カテゴリフィルタを差し替えた新しい状態を返します。
     *
     * @param categoryFilter 次に適用するカテゴリフィルタ
     * @return 更新後の状態
     */
    public @NotNull ItemAdminViewOptions withCategoryFilter(@Nullable String categoryFilter) {
        return new ItemAdminViewOptions(categoryFilter, rarityFilter);
    }

    /**
     * レア度フィルタを差し替えた新しい状態を返します。
     *
     * @param rarityFilter 次に適用するレア度フィルタ
     * @return 更新後の状態
     */
    public @NotNull ItemAdminViewOptions withRarityFilter(@Nullable String rarityFilter) {
        return new ItemAdminViewOptions(categoryFilter, rarityFilter);
    }
}
