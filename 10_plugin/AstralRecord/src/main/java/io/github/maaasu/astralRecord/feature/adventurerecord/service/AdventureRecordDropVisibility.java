package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.jetbrains.annotations.Nullable;

/** 冒険記録のドロップ表示と逆引き検索で共通の公開範囲を定義します。 */
public final class AdventureRecordDropVisibility {
    private AdventureRecordDropVisibility() {
    }

    /**
     * 魔法の卵を隠しアイテムとして除外するか判定します。
     *
     * @param item ロード済みアイテム。未登録の場合は {@code null}
     * @return ペットの卵以外なら {@code true}。未登録は既存の代替表示を維持
     */
    public static boolean isVisible(@Nullable ItemModel item) {
        return item == null || ItemCategory.fromApiValue(item.getCategory()) != ItemCategory.PET_EGG;
    }
}
