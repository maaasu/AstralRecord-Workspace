package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
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
    static final int CONTENT_COLUMNS = 8;
    static final int VISIBLE_ROWS = 3;
    static final int VISIBLE_CAPACITY = CONTENT_COLUMNS * VISIBLE_ROWS;
    static final int DEFAULT_CAPACITY = CONTENT_COLUMNS * 4;
    static final int DB_SLOT_START = 1;
    static final int DB_SLOT_END = DEFAULT_CAPACITY;
    static final int GUI_SLOT_START = 9;
    static final int GUI_SLOT_END = 35;
    static final int SCROLL_UP_GUI_SLOT = 17;
    static final int INFO_GUI_SLOT = 26;
    static final int SCROLL_DOWN_GUI_SLOT = 35;

    static int effectiveCapacity(@NotNull InventoryType inventoryType, @Nullable Integer configuredCapacity) {
        if (inventoryType == InventoryType.BAG) {
            throw new IllegalArgumentException("BAG capacity must come from PlayerInventoryState");
        }
        return configuredCapacity == null ? DEFAULT_CAPACITY : Math.max(0, configuredCapacity);
    }

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

    static boolean isManagedSlot(int slotIndex, int capacity) {
        return slotIndex >= DB_SLOT_START && slotIndex <= Math.max(0, capacity);
    }

    static boolean isManagedGuiSlot(int guiSlot) {
        return guiSlot >= GUI_SLOT_START
            && guiSlot <= GUI_SLOT_END
            && guiSlot != SCROLL_UP_GUI_SLOT
            && guiSlot != INFO_GUI_SLOT
            && guiSlot != SCROLL_DOWN_GUI_SLOT;
    }

    /**
     * API エントリ一覧から管理対象スロットの使用状況を収集します。
     *
     * @param entries インベントリエントリ一覧
     * @return 使用済みスロット集合
     */
    static @NotNull Set<Integer> collectUsedSlots(@NotNull List<InventoryEntryModel> entries) {
        return collectUsedSlots(entries, DEFAULT_CAPACITY);
    }

    static @NotNull Set<Integer> collectUsedSlots(
        @NotNull List<InventoryEntryModel> entries,
        int capacity
    ) {
        Set<Integer> usedSlots = new HashSet<>();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex != null && isManagedSlot(slotIndex, capacity) && !entry.isDeleted()) {
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
            if (!isManagedGuiSlot(guiSlot)) {
                continue;
            }
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
        return findNextFreeSlot(usedSlots, DEFAULT_CAPACITY);
    }

    static @Nullable Integer findNextFreeSlot(@NotNull Set<Integer> usedSlots, int capacity) {
        for (int slot = DB_SLOT_START; slot <= Math.max(0, capacity); slot++) {
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }
        return null;
    }

    static int toGuiSlotIndex(int dbSlot) {
        return GUI_SLOT_START + (dbSlot - DB_SLOT_START);
    }

    static int toGuiSlotIndex(int dbSlot, int scrollRow) {
        int visibleIndex = dbSlot - DB_SLOT_START - Math.max(0, scrollRow) * CONTENT_COLUMNS;
        if (visibleIndex < 0 || visibleIndex >= VISIBLE_CAPACITY) {
            return -1;
        }
        int row = visibleIndex / CONTENT_COLUMNS;
        int column = visibleIndex % CONTENT_COLUMNS;
        return GUI_SLOT_START + row * 9 + column;
    }

    static int toDbSlotIndex(int guiSlot) {
        return DB_SLOT_START + (guiSlot - GUI_SLOT_START);
    }

    static int toDbSlotIndex(int guiSlot, int scrollRow) {
        if (!isManagedGuiSlot(guiSlot)) {
            return -1;
        }
        int relative = guiSlot - GUI_SLOT_START;
        int visibleIndex = (relative / 9) * CONTENT_COLUMNS + relative % 9;
        return DB_SLOT_START + Math.max(0, scrollRow) * CONTENT_COLUMNS + visibleIndex;
    }

    static int totalRows(int capacity) {
        long normalized = Math.max(0L, capacity);
        return Math.max(1, (int) ((normalized + CONTENT_COLUMNS - 1L) / CONTENT_COLUMNS));
    }

    static int maxScrollRow(int capacity) {
        return Math.max(0, totalRows(capacity) - VISIBLE_ROWS);
    }

    /**
     * 利用可能容量と保持中 entry の最大スロットから、GUI で到達可能にする表示容量を返します。
     * 容量外 entry は消さずに表示し、取り出し操作を可能にします。
     */
    static int displayCapacity(@NotNull List<InventoryEntryModel> entries, int usableCapacity) {
        int displayCapacity = Math.max(0, usableCapacity);
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (!entry.isDeleted() && slotIndex != null && slotIndex > displayCapacity) {
                displayCapacity = slotIndex;
            }
        }
        return displayCapacity;
    }

    static long overflowCount(@NotNull List<InventoryEntryModel> entries, int usableCapacity) {
        int capacity = Math.max(0, usableCapacity);
        return entries.stream()
            .filter(entry -> !entry.isDeleted())
            .map(InventoryEntryModel::getSlotIndex)
            .filter(slotIndex -> slotIndex != null && slotIndex > capacity)
            .count();
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
        for (int guiSlot = GUI_SLOT_START; guiSlot <= maxSlot; guiSlot++) {
            if (isManagedGuiSlot(guiSlot)) {
                snapshot[guiSlot] = source[guiSlot];
            }
        }
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
        for (int guiSlot = GUI_SLOT_START; guiSlot <= maxSlot; guiSlot++) {
            if (isManagedGuiSlot(guiSlot)) {
                storage[guiSlot] = snapshot[guiSlot];
            }
        }
        player.getInventory().setStorageContents(storage);
    }
}
