package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ACCESSORY_SLOT インベントリのスロット定義と適用処理を扱います。
 *
 * <p>slot_index 対応表:
 * <ul>
 *   <li>1 = オフハンド (OFF_HAND)</li>
 *   <li>2〜7 = 拡張アクセサリスロット（Bukkit 非対応、DB 管理のみ）</li>
 * </ul>
 */
final class AccessorySlotLayout {

    static final int SLOT_OFF_HAND = AccessorySlotType.OFF_HAND.getSlotIndex();
    static final int SLOT_NECKLACE = AccessorySlotType.NECKLACE.getSlotIndex();
    static final int SLOT_RING = AccessorySlotType.RING.getSlotIndex();
    static final int SLOT_EARRING = AccessorySlotType.EARRING.getSlotIndex();
    static final int SLOT_BRACELET = AccessorySlotType.BRACELET.getSlotIndex();
    static final int SLOT_BELT = AccessorySlotType.BELT.getSlotIndex();
    static final int SLOT_CHARM = AccessorySlotType.CHARM.getSlotIndex();
    static final int SLOT_MIN = SLOT_OFF_HAND;
    static final int SLOT_MAX = SLOT_CHARM;
    static final int CAPACITY = SLOT_MAX;

    private AccessorySlotLayout() {
    }

    static boolean isManagedSlot(int slotIndex) {
        return slotIndex >= SLOT_MIN && slotIndex <= SLOT_MAX;
    }

    /**
     * ACCESSORY_SLOT インベントリのエントリ一覧をプレイヤーのアクセサリスロットへ反映します。
     * slot_index 1 のみ Bukkit オフハンドへ反映します。
     */
    static void applyEntriesToPlayer(
        @NotNull Player player,
        @NotNull List<InventoryEntryModel> entries,
        @NotNull EquipSlotLayout.ItemStackApplier applier
    ) {
        PlayerInventory inventory = player.getInventory();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !isManagedSlot(slotIndex)) {
                continue;
            }
            EquipmentType equipmentType = EquipmentType.fromAccessorySlotIndex(slotIndex.intValue());
            if (equipmentType == EquipmentType.UNSUPPORTED) {
                continue;
            }
            ItemStack itemStack = applier.resolve(entry);
            if (itemStack == null) {
                continue;
            }
            equipmentType.applyTo(inventory, itemStack);
        }
    }

    /**
     * プレイヤーのオフハンドをスナップショット配列として取得します。
     * 返り値の配列インデックスは slot_index と対応します（0 番は未使用）。
     */
    static @NotNull ItemStack[] createSnapshot(@NotNull Player player) {
        ItemStack[] snapshot = new ItemStack[SLOT_MAX + 1];
        snapshot[SLOT_OFF_HAND] = player.getInventory().getItemInOffHand();
        return snapshot;
    }

    /**
     * スナップショット配列をプレイヤーのアクセサリスロットへ反映します。
     */
    static void applySnapshot(@NotNull Player player, @NotNull ItemStack[] snapshot) {
        if (snapshot.length > SLOT_OFF_HAND && snapshot[SLOT_OFF_HAND] != null) {
            player.getInventory().setItemInOffHand(snapshot[SLOT_OFF_HAND]);
            return;
        }
        player.getInventory().setItemInOffHand(new ItemStack(org.bukkit.Material.AIR));
    }
}
