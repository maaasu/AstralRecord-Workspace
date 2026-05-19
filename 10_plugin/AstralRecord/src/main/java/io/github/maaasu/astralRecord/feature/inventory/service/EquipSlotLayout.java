package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EQUIP_SLOT インベントリのスロット定義と適用処理を扱います。
 *
 * <p>slot_index 対応表:
 * <ul>
 *   <li>1 = メインハンド (MAIN_HAND)</li>
 *   <li>2 = 頭 (HEAD)</li>
 *   <li>3 = 胴 (CHEST)</li>
 *   <li>4 = 脚 (LEGS)</li>
 *   <li>5 = 足 (FEET)</li>
 * </ul>
 */
final class EquipSlotLayout {

    static final int SLOT_MAIN_HAND = 1;
    static final int SLOT_HEAD = 2;
    static final int SLOT_CHEST = 3;
    static final int SLOT_LEGS = 4;
    static final int SLOT_FEET = 5;
    static final int SLOT_MIN = SLOT_HEAD;
    static final int SLOT_MAX = SLOT_FEET;

    private EquipSlotLayout() {
    }

    static boolean isManagedSlot(int slotIndex) {
        return slotIndex >= SLOT_MIN && slotIndex <= SLOT_MAX;
    }

    /**
     * EQUIP_SLOT インベントリのエントリ一覧をプレイヤーの装備スロットへ反映します。
     */
    static void applyEntriesToPlayer(
        @NotNull Player player,
        @NotNull List<InventoryEntryModel> entries,
        @NotNull ItemStackApplier applier
    ) {
        PlayerInventory inventory = player.getInventory();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !isManagedSlot(slotIndex)) {
                continue;
            }
            EquipmentType equipmentType = EquipmentType.fromEquipSlotIndex(slotIndex.intValue());
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
     * プレイヤーの現在の装備スロット内容をスナップショット形式で取得します。
     * 返り値の配列インデックスは slot_index と対応します（0 番は未使用）。
     */
    static @NotNull ItemStack[] createSnapshot(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] snapshot = new ItemStack[SLOT_MAX + 1];
        snapshot[SLOT_HEAD] = inventory.getHelmet();
        snapshot[SLOT_CHEST] = inventory.getChestplate();
        snapshot[SLOT_LEGS] = inventory.getLeggings();
        snapshot[SLOT_FEET] = inventory.getBoots();
        return snapshot;
    }

    /**
     * スナップショット配列をプレイヤーの装備スロットへ反映します。
     */
    static void applySnapshot(@NotNull Player player, @NotNull ItemStack[] snapshot) {
        PlayerInventory inventory = player.getInventory();
        inventory.setHelmet(itemOrAir(snapshot, SLOT_HEAD));
        inventory.setChestplate(itemOrAir(snapshot, SLOT_CHEST));
        inventory.setLeggings(itemOrAir(snapshot, SLOT_LEGS));
        inventory.setBoots(itemOrAir(snapshot, SLOT_FEET));
    }

    private static @NotNull ItemStack itemOrAir(@NotNull ItemStack[] snapshot, int slotIndex) {
        if (snapshot.length <= slotIndex) {
            return new ItemStack(org.bukkit.Material.AIR);
        }
        return snapshot[slotIndex];
    }

    /**
     * エントリ解決に使用するコールバック。
     */
    @FunctionalInterface
    interface ItemStackApplier {
        @Nullable ItemStack resolve(@NotNull InventoryEntryModel entry);
    }
}
