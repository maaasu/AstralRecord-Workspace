package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通常インベントリのスロット範囲と空きスロット探索を扱います。
 */
final class NormalInventoryLayout {
    static final int CAPACITY = 27;
    static final int DB_SLOT_START = 1;
    static final int DB_SLOT_END = 27;
    static final int GUI_SLOT_START = 9;
    static final int GUI_SLOT_END = 35;

    private NormalInventoryLayout() {
    }

    /**
     * 通常インベントリで管理対象になるスロットか判定します。
     *
     * @param slotIndex DB保存用の論理スロット番号（1始まり）
     * @return 管理対象なら true
     */
    static boolean isManagedSlot(int slotIndex) {
        return slotIndex >= DB_SLOT_START && slotIndex <= DB_SLOT_END;
    }

    static boolean isManagedGuiSlot(int guiSlot) {
        return guiSlot >= GUI_SLOT_START && guiSlot <= GUI_SLOT_END;
    }

    /**
     * API エントリ一覧から管理対象スロットの使用状況を収集します。
     *
     * @param entries インベントリエントリ一覧
     * @return 使用済みスロット集合
     */
    static @NotNull Set<Integer> collectUsedSlots(@NotNull List<InventoryEntryModel> entries) {
        Set<Integer> usedSlots = new HashSet<>();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex != null && isManagedSlot(slotIndex)) {
                usedSlots.add(slotIndex);
            }
        }
        return usedSlots;
    }

    /**
     * Bukkit インベントリ内容から管理対象スロットの使用状況を収集します。
     *
     * @param contents Bukkit インベントリ内容
     * @return 使用済みスロット集合
     */
    static @NotNull Set<Integer> collectUsedSlots(@NotNull ItemStack[] contents) {
        Set<Integer> usedSlots = new HashSet<>();
        int maxSlot = Math.min(GUI_SLOT_END, contents.length - 1);
        for (int guiSlot = GUI_SLOT_START; guiSlot <= maxSlot; guiSlot++) {
            ItemStack itemStack = contents[guiSlot];
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                int dbSlot = toDbSlotIndex(guiSlot);
                if (isManagedSlot(dbSlot)) {
                    usedSlots.add(dbSlot);
                }
            }
        }
        return usedSlots;
    }

    /**
     * 通常インベントリの管理対象範囲で最初の空きスロットを返します。
     *
     * @param usedSlots 使用済みスロット集合
     * @return 空きスロット。存在しない場合は null
     */
    static @Nullable Integer findNextFreeSlot(@NotNull Set<Integer> usedSlots) {
        for (int slot = DB_SLOT_START; slot <= DB_SLOT_END; slot++) {
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }
        return null;
    }

    static int toGuiSlotIndex(int dbSlot) {
        return GUI_SLOT_START + (dbSlot - DB_SLOT_START);
    }

    static int toDbSlotIndex(int guiSlot) {
        return DB_SLOT_START + (guiSlot - GUI_SLOT_START);
    }

    /**
     * プレイヤーのストレージ内容から通常インベントリ管理範囲だけを抽出します。
     *
     * @param player 抽出元プレイヤー
     * @return ホットバーなど管理外スロットを空にしたストレージ内容
     */
    static @NotNull ItemStack[] createManagedStorageSnapshot(@NotNull Player player) {
        ItemStack[] source = player.getInventory().getStorageContents();
        ItemStack[] snapshot = new ItemStack[source.length];
        int maxSlot = Math.min(GUI_SLOT_END, source.length - 1);
        if (maxSlot - 8 >= 0) System.arraycopy(source, GUI_SLOT_START, snapshot, GUI_SLOT_START, maxSlot - 8);
        return snapshot;
    }

    /**
     * 復元済みスナップショットから通常インベントリ管理範囲だけをプレイヤーへ反映します。
     *
     * @param player 反映先プレイヤー
     * @param snapshot 復元済みスナップショット
     */
    static void applyManagedStorageSnapshot(@NotNull Player player, @NotNull ItemStack[] snapshot) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        int maxSlot = Math.min(Math.min(GUI_SLOT_END, storage.length - 1), snapshot.length - 1);
        if (maxSlot - 8 >= 0) System.arraycopy(snapshot, GUI_SLOT_START, storage, GUI_SLOT_START, maxSlot - 8);
        player.getInventory().setStorageContents(storage);
    }
}
