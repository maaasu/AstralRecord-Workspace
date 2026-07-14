package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EquipmentMenuScreenView extends BaseMenuScreenView {
    private static final Color EMPTY_ARMOR_COLOR = Color.fromRGB(48, 48, 48);

    public static final int PLAYER_STATUS_SLOT = 0;
    public static final int PET_SLOT = 16;
    public static final int EQUIPMENT_BACK_SLOT = BACK_SLOT;
    public static final int EQUIPMENT_MAIN_HAND_SLOT = 19;
    public static final int EQUIPMENT_HEAD_SLOT = 11;
    public static final int EQUIPMENT_CHEST_SLOT = 20;
    public static final int EQUIPMENT_LEGS_SLOT = 29;
    public static final int EQUIPMENT_FEET_SLOT = 38;
    public static final int EQUIPMENT_OFF_HAND_SLOT = 21;
    public static final int MEMORY_1_SLOT = 27;
    public static final int MEMORY_2_SLOT = 36;
    public static final int EQUIPMENT_AMULET_SLOT = 23;
    public static final int EQUIPMENT_TALISMAN_1_SLOT = 31;
    public static final int EQUIPMENT_TALISMAN_2_SLOT = 33;
    public static final int EQUIPMENT_CORE_SLOT = 32;
    public static final int EQUIPMENT_RELIC_1_SLOT = 39;
    public static final int EQUIPMENT_CHARM_1_SLOT = 40;
    public static final int EQUIPMENT_CHARM_2_SLOT = 41;
    public static final int EQUIPMENT_CHARM_3_SLOT = 42;
    public static final int EQUIPMENT_RELIC_2_SLOT = 43;
    public static final int GAUGE_LARGE_SLOT = 26;
    public static final int GAUGE_MEDIUM_SLOT = 35;
    public static final int GAUGE_SMALL_SLOT = 44;

    private final NamespacedKey equipmentPlaceholderKey;

    public EquipmentMenuScreenView(@NotNull NamespacedKey equipmentPlaceholderKey) {
        this.equipmentPlaceholderKey = equipmentPlaceholderKey;
    }

    /**
     * 装備 GUI の全スロットを現在のプレイヤー装備で描画します。
     *
     * @param inventory 描画対象のチェストインベントリ
     * @param player 対象プレイヤー
     * @param accessories slotIndex と配列 index が一致するアクセサリスナップショット
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull Player player,
        @NotNull ItemStack[] accessories
    ) {
        fill(inventory);
        PlayerInventory playerInventory = player.getInventory();
        inventory.setItem(PLAYER_STATUS_SLOT, playerStatusItem(player));
        updateMainHandItem(inventory, playerInventory.getItemInMainHand());
        inventory.setItem(EQUIPMENT_HEAD_SLOT, itemOrPlaceholderForSlot(playerInventory.getHelmet(), EQUIPMENT_HEAD_SLOT));
        inventory.setItem(EQUIPMENT_CHEST_SLOT, itemOrPlaceholderForSlot(playerInventory.getChestplate(), EQUIPMENT_CHEST_SLOT));
        inventory.setItem(EQUIPMENT_LEGS_SLOT, itemOrPlaceholderForSlot(playerInventory.getLeggings(), EQUIPMENT_LEGS_SLOT));
        inventory.setItem(EQUIPMENT_FEET_SLOT, itemOrPlaceholderForSlot(playerInventory.getBoots(), EQUIPMENT_FEET_SLOT));
        inventory.setItem(EQUIPMENT_OFF_HAND_SLOT, itemOrPlaceholderForSlot(playerInventory.getItemInOffHand(), EQUIPMENT_OFF_HAND_SLOT));
        for (AccessorySlotType type : AccessorySlotType.values()) {
            if (!type.isAccessory()) {
                continue;
            }
            int guiSlot = getGuiSlotForAccessoryType(type);
            inventory.setItem(guiSlot, itemOrPlaceholderForSlot(accessoryAt(accessories, type.getSlotIndex()), guiSlot));
        }
        inventory.setItem(MEMORY_1_SLOT, futureSlot(Material.HOPPER, "メモリースロット 1"));
        inventory.setItem(MEMORY_2_SLOT, futureSlot(Material.HOPPER, "メモリースロット 2"));
        inventory.setItem(GAUGE_LARGE_SLOT, futureSlot(Material.SPAWNER, "ラージゲージ"));
        inventory.setItem(GAUGE_MEDIUM_SLOT, futureSlot(Material.SPAWNER, "ミディアムゲージ"));
        inventory.setItem(GAUGE_SMALL_SLOT, futureSlot(Material.SPAWNER, "スモールゲージ"));
        inventory.setItem(PET_SLOT, futureSlot(Material.SADDLE, "ペットスロット"));
        inventory.setItem(EQUIPMENT_BACK_SLOT, backItem());
    }

    /**
     * 選択中ホットバーのアイテムをメインスロット表示へ反映します。
     *
     * @param inventory 装備 GUI
     * @param itemStack 新しく選択されたホットバーアイテム
     */
    public void updateMainHandItem(@NotNull Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack != null && itemStack.getType() != Material.AIR) {
            inventory.setItem(EQUIPMENT_MAIN_HAND_SLOT, itemStack.clone());
            return;
        }
        inventory.setItem(
            EQUIPMENT_MAIN_HAND_SLOT,
            equipmentPlaceholder(Material.ITEM_FRAME, "メインハンド", "選択中のホットバーアイテム")
        );
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

    /**
     * GUI の物理スロットから種類別アクセサリスロットを解決します。
     *
     * @param rawSlot GUI の物理スロット
     * @return 対応するアクセサリ種別。対象外の場合は null
     */
    public @Nullable AccessorySlotType getAccessorySlotTypeAtSlot(int rawSlot) {
        return switch (rawSlot) {
            case EQUIPMENT_AMULET_SLOT -> AccessorySlotType.AMULET;
            case EQUIPMENT_TALISMAN_1_SLOT -> AccessorySlotType.TALISMAN_1;
            case EQUIPMENT_TALISMAN_2_SLOT -> AccessorySlotType.TALISMAN_2;
            case EQUIPMENT_CHARM_1_SLOT -> AccessorySlotType.CHARM_1;
            case EQUIPMENT_CHARM_2_SLOT -> AccessorySlotType.CHARM_2;
            case EQUIPMENT_CHARM_3_SLOT -> AccessorySlotType.CHARM_3;
            case EQUIPMENT_CORE_SLOT -> AccessorySlotType.CORE;
            case EQUIPMENT_RELIC_1_SLOT -> AccessorySlotType.RELIC_1;
            case EQUIPMENT_RELIC_2_SLOT -> AccessorySlotType.RELIC_2;
            default -> null;
        };
    }

    public boolean isExtendedAccessorySlot(int rawSlot) {
        return getAccessorySlotTypeAtSlot(rawSlot) != null;
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

    /**
     * 指定したアクセサリ種別と同じ tag を受け入れる最初の空き枠を返します。
     *
     * @param inventory 装備 GUI
     * @param requestedType 配置するアクセサリ種別
     * @return 空き GUI スロット。空きがない場合は -1
     */
    public int firstEmptyAccessorySlot(
        @NotNull Inventory inventory,
        @NotNull AccessorySlotType requestedType
    ) {
        for (AccessorySlotType type : AccessorySlotType.values()) {
            if (!type.matchesEquipmentTag(requestedType.getEquipmentTag())) {
                continue;
            }
            int guiSlot = getGuiSlotForAccessoryType(type);
            if (getEquipmentGuiItem(inventory, guiSlot) == null) {
                return guiSlot;
            }
        }
        return -1;
    }

    /**
     * GUI 上のオフハンドと種類別アクセサリをスロット番号対応配列で返します。
     *
     * @param inventory 装備 GUI
     * @return ACCESSORY_SLOT の slotIndex と配列 index が一致する一覧
     */
    public @NotNull ItemStack[] getAccessoryItems(@NotNull Inventory inventory) {
        ItemStack[] items = new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1];
        items[AccessorySlotType.OFF_HAND.getSlotIndex()] = getEquipmentGuiItem(inventory, EQUIPMENT_OFF_HAND_SLOT);
        for (AccessorySlotType type : AccessorySlotType.values()) {
            if (type.isAccessory()) {
                items[type.getSlotIndex()] = getEquipmentGuiItem(inventory, getGuiSlotForAccessoryType(type));
            }
        }
        return items;
    }

    public boolean isEquipmentPlaceholder(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(equipmentPlaceholderKey, PersistentDataType.INTEGER);
    }

    public @Nullable ItemStack createPlaceholderForSlot(int slot) {
        return switch (slot) {
            case EQUIPMENT_HEAD_SLOT -> equipmentPlaceholder(Material.LEATHER_HELMET, "頭", "頭防具スロット");
            case EQUIPMENT_CHEST_SLOT -> equipmentPlaceholder(Material.LEATHER_CHESTPLATE, "胴", "胴防具スロット");
            case EQUIPMENT_LEGS_SLOT -> equipmentPlaceholder(Material.LEATHER_LEGGINGS, "脚", "脚防具スロット");
            case EQUIPMENT_FEET_SLOT -> equipmentPlaceholder(Material.LEATHER_BOOTS, "足", "足防具スロット");
            case EQUIPMENT_OFF_HAND_SLOT -> equipmentPlaceholder(Material.GLOW_ITEM_FRAME, "オフハンド", "サブ武器スロット");
            case EQUIPMENT_AMULET_SLOT -> equipmentPlaceholder(Material.CHEST_MINECART, "アミュレット", "アミュレットスロット");
            case EQUIPMENT_TALISMAN_1_SLOT -> equipmentPlaceholder(Material.FURNACE_MINECART, "タリスマン 1", "タリスマンスロット 1/2");
            case EQUIPMENT_TALISMAN_2_SLOT -> equipmentPlaceholder(Material.FURNACE_MINECART, "タリスマン 2", "タリスマンスロット 2/2");
            case EQUIPMENT_CHARM_1_SLOT -> equipmentPlaceholder(Material.MINECART, "チャーム 1", "チャームスロット 1/3");
            case EQUIPMENT_CHARM_2_SLOT -> equipmentPlaceholder(Material.MINECART, "チャーム 2", "チャームスロット 2/3");
            case EQUIPMENT_CHARM_3_SLOT -> equipmentPlaceholder(Material.MINECART, "チャーム 3", "チャームスロット 3/3");
            case EQUIPMENT_CORE_SLOT -> equipmentPlaceholder(Material.HOPPER_MINECART, "コア", "コアスロット");
            case EQUIPMENT_RELIC_1_SLOT -> equipmentPlaceholder(Material.TNT_MINECART, "レリック 1", "レリックスロット 1/2");
            case EQUIPMENT_RELIC_2_SLOT -> equipmentPlaceholder(Material.TNT_MINECART, "レリック 2", "レリックスロット 2/2");
            default -> null;
        };
    }

    private int getGuiSlotForAccessoryType(@NotNull AccessorySlotType type) {
        return switch (type) {
            case AMULET -> EQUIPMENT_AMULET_SLOT;
            case TALISMAN_1 -> EQUIPMENT_TALISMAN_1_SLOT;
            case TALISMAN_2 -> EQUIPMENT_TALISMAN_2_SLOT;
            case CHARM_1 -> EQUIPMENT_CHARM_1_SLOT;
            case CHARM_2 -> EQUIPMENT_CHARM_2_SLOT;
            case CHARM_3 -> EQUIPMENT_CHARM_3_SLOT;
            case CORE -> EQUIPMENT_CORE_SLOT;
            case RELIC_1 -> EQUIPMENT_RELIC_1_SLOT;
            case RELIC_2 -> EQUIPMENT_RELIC_2_SLOT;
            case OFF_HAND -> EQUIPMENT_OFF_HAND_SLOT;
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

    private @NotNull ItemStack equipmentPlaceholder(
        @NotNull Material material,
        @NotNull String title,
        @NotNull String description
    ) {
        ItemStack placeholder = createItem(
            material,
            Component.text(title, NamedTextColor.DARK_GRAY),
            List.of(Component.text(description, NamedTextColor.GRAY))
        );
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            if (meta instanceof LeatherArmorMeta leatherArmorMeta) {
                leatherArmorMeta.setColor(EMPTY_ARMOR_COLOR);
            }
            meta.getPersistentDataContainer().set(equipmentPlaceholderKey, PersistentDataType.INTEGER, 1);
            placeholder.setItemMeta(meta);
        }
        return placeholder;
    }

    private @NotNull ItemStack playerStatusItem(@NotNull Player player) {
        ItemStack itemStack = createItem(
            Material.PLAYER_HEAD,
            Component.text(player.getName(), NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text("クリックしてステータスを表示", NamedTextColor.GRAY))
        );
        if (itemStack.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private @NotNull ItemStack futureSlot(@NotNull Material material, @NotNull String title) {
        return createItem(
            material,
            Component.text(title, NamedTextColor.DARK_GRAY),
            List.of(Component.text("将来拡張用スロット", NamedTextColor.GRAY))
        );
    }

}
