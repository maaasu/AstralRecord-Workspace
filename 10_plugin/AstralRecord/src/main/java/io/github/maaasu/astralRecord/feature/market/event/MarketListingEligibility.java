package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.jetbrains.annotations.Nullable;

/** マーケット出品画面で行う、安全な事前選択判定をまとめます。 */
final class MarketListingEligibility {
    private MarketListingEligibility() {
    }

    /**
     * 所持品 entry をマーケット出品候補として選択できるか判定します。
     * API が escrow・所有権・出品枠などの業務判定を最終確定するため、ここでは
     * 取引不可・Gold・不正なentry形式だけを早期に除外します。売却不可フラグと売値は
     * マーケット対象外の条件ではなく、価格ガードで単価と比較します。
     *
     * @param entry 所持品正本 entry
     * @param item entry に対応するアイテム定義
     * @return 出品設定へ進める候補の場合は {@code true}
     */
    static boolean isEligible(@Nullable InventoryEntryModel entry, @Nullable ItemModel item) {
        if (entry == null
            || item == null
            || entry.isDeleted()
            || entry.getItemId() == null
            || entry.getItemId().isBlank()
            || item.getUnTradeable()) {
            return false;
        }

        ItemCategory category = ItemCategory.fromApiValue(entry.getItemCategory());
        if (category == ItemCategory.UNKNOWN || category == ItemCategory.CURRENCY) {
            return false;
        }

        if (entry.getInstanceId() == null) {
            return entry.getQuantity() > 0L && isBlank(entry.getInstanceType());
        }

        InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
        return entry.getQuantity() == 1L
            && instanceType == InventoryInstanceType.EQUIPMENT
            && category == ItemCategory.EQUIPMENT;
    }

    /**
     * null と空白だけを、stack item に許可される未指定 instance type として扱います。
     *
     * @param value 判定対象文字列
     * @return 未指定の場合は {@code true}
     */
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
