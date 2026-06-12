package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EquipmentMenuScreenView extends BaseMenuScreenView {
    public static final int ENHANCEMENT_SLOT = 15;
    public static final int EQUIPMENT_HEAD_SLOT = 11;
    public static final int EQUIPMENT_CHEST_SLOT = 20;
    public static final int EQUIPMENT_LEGS_SLOT = 29;
    public static final int EQUIPMENT_FEET_SLOT = 38;
    public static final int EQUIPMENT_OFF_HAND_SLOT = 25;
    public static final int EQUIPMENT_ACCESSORY_2_SLOT = 22;
    public static final int EQUIPMENT_ACCESSORY_3_SLOT = 23;
    public static final int EQUIPMENT_ACCESSORY_4_SLOT = 31;
    public static final int EQUIPMENT_ACCESSORY_5_SLOT = 32;
    public static final int EQUIPMENT_ACCESSORY_6_SLOT = 40;
    public static final int EQUIPMENT_ACCESSORY_7_SLOT = 41;
    private static final int[] ACCESSORY_SLOTS = {
        EQUIPMENT_OFF_HAND_SLOT,
        EQUIPMENT_ACCESSORY_2_SLOT,
        EQUIPMENT_ACCESSORY_3_SLOT,
        EQUIPMENT_ACCESSORY_4_SLOT,
        EQUIPMENT_ACCESSORY_5_SLOT,
        EQUIPMENT_ACCESSORY_6_SLOT,
        EQUIPMENT_ACCESSORY_7_SLOT
    };

    private final NamespacedKey equipmentPlaceholderKey;

    public EquipmentMenuScreenView(@NotNull NamespacedKey equipmentPlaceholderKey) {
        this.equipmentPlaceholderKey = equipmentPlaceholderKey;
    }

    public void render(
        @NotNull Inventory inventory,
        @NotNull Player player,
        @NotNull ItemStack[] accessories
    ) {
        fill(inventory);
        PlayerInventory playerInventory = player.getInventory();
        inventory.setItem(EQUIPMENT_HEAD_SLOT, itemOrPlaceholderForSlot(playerInventory.getHelmet(), EQUIPMENT_HEAD_SLOT));
        inventory.setItem(EQUIPMENT_CHEST_SLOT, itemOrPlaceholderForSlot(playerInventory.getChestplate(), EQUIPMENT_CHEST_SLOT));
        inventory.setItem(EQUIPMENT_LEGS_SLOT, itemOrPlaceholderForSlot(playerInventory.getLeggings(), EQUIPMENT_LEGS_SLOT));
        inventory.setItem(EQUIPMENT_FEET_SLOT, itemOrPlaceholderForSlot(playerInventory.getBoots(), EQUIPMENT_FEET_SLOT));
        inventory.setItem(EQUIPMENT_OFF_HAND_SLOT, itemOrPlaceholderForSlot(playerInventory.getItemInOffHand(), EQUIPMENT_OFF_HAND_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_2_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 2), EQUIPMENT_ACCESSORY_2_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_3_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 3), EQUIPMENT_ACCESSORY_3_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_4_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 4), EQUIPMENT_ACCESSORY_4_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_5_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 5), EQUIPMENT_ACCESSORY_5_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_6_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 6), EQUIPMENT_ACCESSORY_6_SLOT));
        inventory.setItem(EQUIPMENT_ACCESSORY_7_SLOT, itemOrPlaceholderForSlot(accessoryAt(accessories, 7), EQUIPMENT_ACCESSORY_7_SLOT));
        inventory.setItem(ENHANCEMENT_SLOT, createItem(
            Material.ANVIL,
            Component.text("装備強化", NamedTextColor.GOLD),
            List.of(
                Component.text("武器の強化を行うGUIを開きます。", NamedTextColor.GRAY),
                Component.text("下段インベントリは装備一覧に切り替わります。", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(BACK_SLOT, backItem());
    }

    public @Nullable EquipmentType getEquipmentTypeAtSlot(int rawSlot) {
        return switch (rawSlot) {
            case EQUIPMENT_HEAD_SLOT -> EquipmentType.HEAD;
            case EQUIPMENT_CHEST_SLOT -> EquipmentType.CHEST;
            case EQUIPMENT_LEGS_SLOT -> EquipmentType.LEGS;
            case EQUIPMENT_FEET_SLOT -> EquipmentType.FEET;
            case EQUIPMENT_OFF_HAND_SLOT -> EquipmentType.OFF_HAND;
            default -> null;
        };
    }

    public boolean isExtendedAccessorySlot(int rawSlot) {
        return rawSlot == EQUIPMENT_ACCESSORY_2_SLOT
            || rawSlot == EQUIPMENT_ACCESSORY_3_SLOT
            || rawSlot == EQUIPMENT_ACCESSORY_4_SLOT
            || rawSlot == EQUIPMENT_ACCESSORY_5_SLOT
            || rawSlot == EQUIPMENT_ACCESSORY_6_SLOT
            || rawSlot == EQUIPMENT_ACCESSORY_7_SLOT;
    }

    public boolean isEquipmentItemSlot(int rawSlot) {
        return getEquipmentTypeAtSlot(rawSlot) != null || isExtendedAccessorySlot(rawSlot);
    }

    public @Nullable ItemStack getEquipmentGuiItem(@NotNull Inventory inventory, int slot) {
        ItemStack itemStack = inventory.getItem(slot);
        if (itemStack == null || itemStack.getType() == Material.AIR || isEquipmentPlaceholder(itemStack)) {
            return null;
        }
        return itemStack;
    }

    public int getSlotForEquipmentType(@NotNull EquipmentType equipmentType) {
        return switch (equipmentType) {
            case HEAD -> EQUIPMENT_HEAD_SLOT;
            case CHEST -> EQUIPMENT_CHEST_SLOT;
            case LEGS -> EQUIPMENT_LEGS_SLOT;
            case FEET -> EQUIPMENT_FEET_SLOT;
            case OFF_HAND -> EQUIPMENT_OFF_HAND_SLOT;
            default -> -1;
        };
    }

    public int firstEmptyAccessorySlot(@NotNull Inventory inventory) {
        for (int slot : ACCESSORY_SLOTS) {
            if (getEquipmentGuiItem(inventory, slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    public @NotNull ItemStack[] getAccessoryItems(@NotNull Inventory inventory) {
        ItemStack[] items = new ItemStack[8];
        items[1] = getEquipmentGuiItem(inventory, EQUIPMENT_OFF_HAND_SLOT);
        items[2] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_2_SLOT);
        items[3] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_3_SLOT);
        items[4] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_4_SLOT);
        items[5] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_5_SLOT);
        items[6] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_6_SLOT);
        items[7] = getEquipmentGuiItem(inventory, EQUIPMENT_ACCESSORY_7_SLOT);
        return items;
    }

    public boolean isEquipmentPlaceholder(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(equipmentPlaceholderKey, PersistentDataType.INTEGER);
    }

    /**
     * 指定スロットの装備種別に対応するプレースホルダー ItemStack を生成します。
     *
     * @param slot 装備 GUI スロット番号
     * @return プレースホルダー（管理外スロットなら null）
     */
    public @Nullable ItemStack createPlaceholderForSlot(int slot) {
        return switch (slot) {
            case EQUIPMENT_HEAD_SLOT -> placeholder("頭", "頭防具スロット");
            case EQUIPMENT_CHEST_SLOT -> placeholder("胴", "胴防具スロット");
            case EQUIPMENT_LEGS_SLOT -> placeholder("脚", "脚防具スロット");
            case EQUIPMENT_FEET_SLOT -> placeholder("足", "足防具スロット");
            case EQUIPMENT_OFF_HAND_SLOT -> placeholder("オフハンド", "盾またはアクセサリ");
            case EQUIPMENT_ACCESSORY_2_SLOT -> placeholder("首飾り", "アクセサリスロット");
            case EQUIPMENT_ACCESSORY_3_SLOT -> placeholder("指輪", "アクセサリスロット");
            case EQUIPMENT_ACCESSORY_4_SLOT -> placeholder("耳飾り", "アクセサリスロット");
            case EQUIPMENT_ACCESSORY_5_SLOT -> placeholder("腕輪", "アクセサリスロット");
            case EQUIPMENT_ACCESSORY_6_SLOT -> placeholder("ベルト", "アクセサリスロット");
            case EQUIPMENT_ACCESSORY_7_SLOT -> placeholder("護符", "アクセサリスロット");
            default -> null;
        };
    }

    private @Nullable ItemStack accessoryAt(@NotNull ItemStack[] accessories, int slotIndex) {
        return accessories.length > slotIndex ? accessories[slotIndex] : null;
    }

    private @NotNull ItemStack itemOrPlaceholderForSlot(@Nullable ItemStack itemStack, int slot) {
        if (itemStack != null && itemStack.getType() != Material.AIR) {
            return itemStack;
        }
        ItemStack placeholder = createPlaceholderForSlot(slot);
        return placeholder != null ? placeholder : new ItemStack(Material.AIR);
    }

    private @NotNull ItemStack placeholder(@NotNull String title, @NotNull String description) {
        ItemStack placeholder = createItem(
            Material.MINECART,
            Component.text(title, NamedTextColor.DARK_GRAY),
            List.of(Component.text(description, NamedTextColor.GRAY))
        );
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(equipmentPlaceholderKey, PersistentDataType.INTEGER, 1);
            placeholder.setItemMeta(meta);
        }
        return placeholder;
    }
}
