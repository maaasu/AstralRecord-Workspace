package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.bukkit.Material;
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
 *   <li>2〜10 = 種類別アクセサリスロット（Bukkit 非対応、DB 管理のみ）</li>
 * </ul>
 */
final class AccessorySlotLayout {

    static final int SLOT_OFF_HAND = AccessorySlotType.OFF_HAND.getSlotIndex();
    static final int SLOT_AMULET = AccessorySlotType.AMULET.getSlotIndex();
    static final int SLOT_TALISMAN_1 = AccessorySlotType.TALISMAN_1.getSlotIndex();
    static final int SLOT_TALISMAN_2 = AccessorySlotType.TALISMAN_2.getSlotIndex();
    static final int SLOT_CHARM_1 = AccessorySlotType.CHARM_1.getSlotIndex();
    static final int SLOT_CHARM_2 = AccessorySlotType.CHARM_2.getSlotIndex();
    static final int SLOT_CHARM_3 = AccessorySlotType.CHARM_3.getSlotIndex();
    static final int SLOT_CORE = AccessorySlotType.CORE.getSlotIndex();
    static final int SLOT_RELIC_1 = AccessorySlotType.RELIC_1.getSlotIndex();
    static final int SLOT_RELIC_2 = AccessorySlotType.RELIC_2.getSlotIndex();
    static final int SLOT_MIN = SLOT_OFF_HAND;
    static final int SLOT_MAX = SLOT_RELIC_2;
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
            EquipmentType equipmentType = EquipmentType.fromAccessorySlotIndex(slotIndex);
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
     * <p>
     * 既存オフハンドと表示上同一の場合は再設置せず、不要な装備アニメーションや
     * 同等アイテムの再配置を抑制します。
     */
    static void applySnapshot(@NotNull Player player, @NotNull ItemStack[] snapshot) {
        ItemStack desired = snapshot.length > SLOT_OFF_HAND
            ? snapshot[SLOT_OFF_HAND]
            : new ItemStack(Material.AIR);
        PlayerInventory inventory = player.getInventory();
        if (isSameOffHand(inventory.getItemInOffHand(), desired)) {
            return;
        }
        inventory.setItemInOffHand(desired);
    }

    private static boolean isSameOffHand(@Nullable ItemStack current, @Nullable ItemStack next) {
        boolean currentEmpty = current == null || current.getType() == Material.AIR;
        boolean nextEmpty = next == null || next.getType() == Material.AIR;
        if (currentEmpty || nextEmpty) {
            return currentEmpty == nextEmpty;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }
}
