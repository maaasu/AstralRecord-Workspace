package io.github.maaasu.astralRecord.feature.storage.model;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;

/**
 * ストレージ GUI に表示する 1 行分の情報です。
 *
 * @param entry 永続化対象の inventory entry
 * @param itemStack 表示用 ItemStack
 * @param itemModel アイテムマスタ
 * @param acquiredAt 獲得順ソートに使用する日時
 */
public record StorageViewEntry(
    @NotNull InventoryEntryModel entry,
    @NotNull ItemStack itemStack,
    @NotNull ItemModel itemModel,
    @NotNull LocalDateTime acquiredAt
) {
}
