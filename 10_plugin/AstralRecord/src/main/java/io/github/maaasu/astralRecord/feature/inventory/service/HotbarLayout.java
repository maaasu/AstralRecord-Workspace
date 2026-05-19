package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * HOTBAR インベントリのスロット範囲とスナップショット処理を扱います。
 *
 * <p>DB slot_index と Bukkit スロットの対応:
 * <ul>
 *   <li>DB slot_index 1〜9 ↔ Bukkit storageContents[0〜8]</li>
 * </ul>
 */
public final class HotbarLayout {

    public static final int DB_SLOT_START = 1;
    public static final int DB_SLOT_END = 9;
    /** オフハンドをホットバーの拡張スロットとして扱うための DB slot_index */
    public static final int DB_SLOT_OFFHAND = 10;
    public static final int BUKKIT_SLOT_START = 0;
    public static final int BUKKIT_SLOT_END = 8;
    /** ホットバー本体（1〜9）+ オフハンド（10）の合計容量 */
    public static final int CAPACITY = DB_SLOT_OFFHAND - DB_SLOT_START + 1;

    private HotbarLayout() {
    }

    static boolean isManagedSlot(int dbSlotIndex) {
        return dbSlotIndex >= DB_SLOT_START && dbSlotIndex <= DB_SLOT_OFFHAND;
    }

    /**
     * 指定 DB slot_index がオフハンド（拡張ホットバー）かどうかを判定します。
     *
     * @param dbSlotIndex DB の slot_index
     * @return オフハンドなら true
     */
    static boolean isOffhandSlot(int dbSlotIndex) {
        return dbSlotIndex == DB_SLOT_OFFHAND;
    }

    /**
     * 指定 DB slot_index がホットバー本体（1〜9）かどうかを判定します。
     *
     * @param dbSlotIndex DB の slot_index
     * @return ホットバー本体なら true
     */
    static boolean isMainHotbarSlot(int dbSlotIndex) {
        return dbSlotIndex >= DB_SLOT_START && dbSlotIndex <= DB_SLOT_END;
    }

    static int toBukkitSlot(int dbSlotIndex) {
        return BUKKIT_SLOT_START + (dbSlotIndex - DB_SLOT_START);
    }

    static int toDbSlot(int bukkitSlot) {
        return DB_SLOT_START + (bukkitSlot - BUKKIT_SLOT_START);
    }

    /**
     * プレイヤーのホットバー（Bukkit スロット 0〜8）をスナップショット配列として取得します。
     * 返り値の配列インデックスは Bukkit スロット番号と一致します。
     */
    static @NotNull ItemStack[] createSnapshot(@NotNull Player player) {
        var storage = player.getInventory().getStorageContents();
        var snapshot = new ItemStack[BUKKIT_SLOT_END + 1];
        int len = Math.min(BUKKIT_SLOT_END + 1, storage.length);
        System.arraycopy(storage, BUKKIT_SLOT_START, snapshot, BUKKIT_SLOT_START, len);
        return snapshot;
    }

    /**
     * スナップショット配列をプレイヤーのホットバー（Bukkit スロット 0〜8）へ反映します。
     */
    static void applySnapshot(@NotNull Player player, @NotNull ItemStack[] snapshot) {
        var storage = player.getInventory().getStorageContents();
        int len = Math.min(BUKKIT_SLOT_END + 1, Math.min(snapshot.length, storage.length));
        for (int i = BUKKIT_SLOT_START; i < len; i++) {
            storage[i] = itemOrAir(snapshot[i]);
        }
        player.getInventory().setStorageContents(storage);
    }

    static void applyEntriesToPlayer(
        @NotNull Player player,
        @NotNull List<InventoryEntryModel> entries,
        @NotNull EquipSlotLayout.ItemStackApplier applier
    ) {
        var storage = player.getInventory().getStorageContents();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !isManagedSlot(slotIndex) || entry.isDeleted()) {
                continue;
            }
            int bukkitSlot = toBukkitSlot(slotIndex);
            if (bukkitSlot < 0 || bukkitSlot >= storage.length) {
                continue;
            }
            storage[bukkitSlot] = itemOrAir(applier.resolve(entry));
        }
        player.getInventory().setStorageContents(storage);
    }

    private static @NotNull ItemStack itemOrAir(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return new ItemStack(Material.AIR);
        }
        return itemStack;
    }
}
