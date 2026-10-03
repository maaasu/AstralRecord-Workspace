package io.github.maaasu.astralRecord.feature.pet.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import java.util.List;

/** ペットAPIと同じGAME BAGの範囲で、個体と孵化・配合・復活の素材を参照します。 */
public final class PetBagAccess {
    private PetBagAccess() { }

    /**
     * 有効なGAME BAGの未削除entryをスナップショットで返します。
     * @param state 読み込み済みインベントリ。nullなら空
     * @return ホットバー・倉庫・管理者インベントリを含まないentry一覧
     */
    public static List<InventoryEntryModel> entries(PlayerInventoryState state) {
        if (state == null) return List.of();
        synchronized (state) {
            var bag = state.findInventory(InventoryProfile.GAME, InventoryType.BAG);
            if (bag == null || bag.isDeleted() || !bag.isEnabled()) return List.of();
            return state.snapshotEntries(bag.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted()).toList();
        }
    }

    /**
     * APIの素材消費と同じく、個体を持たない通常entryだけを合算します。
     * @param entries GAME BAGのentry一覧
     * @param itemId 消費するマスターID
     * @return 所持数量。集計がlongの上限を超える場合は上限値
     */
    public static long materialAmount(List<InventoryEntryModel> entries, String itemId) {
        long total = 0L;
        for (var entry : entries) {
            if (entry.isDeleted() || entry.getInstanceId() != null || entry.getInstanceType() != null
                || entry.getItemId() == null || !entry.getItemId().equalsIgnoreCase(itemId)) continue;
            long quantity = Math.max(0L, entry.getQuantity());
            total = total > Long.MAX_VALUE - quantity ? Long.MAX_VALUE : total + quantity;
        }
        return total;
    }
}
