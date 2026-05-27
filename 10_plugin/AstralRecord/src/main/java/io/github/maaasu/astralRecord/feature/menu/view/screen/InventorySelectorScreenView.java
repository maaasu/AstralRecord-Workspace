package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class InventorySelectorScreenView extends BaseMenuScreenView {
    private static final int[] INVENTORY_TYPE_SLOTS = {21, 22, 23};
    public static final int TRASH_SLOT = 24;

    /**
     * 種別選択メニューを描画します。選択中の種別は発光で強調表示します。
     *
     * @param inventory 描画先
     * @param selectedType 現在選択中の種別
     */
    public void render(@NotNull Inventory inventory, @Nullable InventoryType selectedType) {
        fill(inventory);
        InventoryType[] types = selectableInventoryTypes();
        for (int i = 0; i < INVENTORY_TYPE_SLOTS.length && i < types.length; i++) {
            MenuShortcutAction action = actionForInventoryType(types[i]);
            ItemStack icon = createItem(
                action.getMaterial(),
                Component.text(action.getDisplayNameJa(), action.getColor()),
                List.of(Component.text("クリックして表示する", NamedTextColor.GRAY))
            );
            if (types[i] == selectedType) {
                icon = withSelectionGlow(icon);
            }
            inventory.setItem(INVENTORY_TYPE_SLOTS[i], icon);
        }
        inventory.setItem(TRASH_SLOT, createItem(
            org.bukkit.Material.LAVA_BUCKET,
            Component.text("ゴミ箱", NamedTextColor.RED),
            List.of(Component.text("アイテムを廃棄する", NamedTextColor.GRAY))
        ));
        inventory.setItem(BACK_SLOT, backItem());
    }

    public @Nullable InventoryType getInventoryTypeAtSlot(int rawSlot) {
        InventoryType[] types = selectableInventoryTypes();
        for (int i = 0; i < INVENTORY_TYPE_SLOTS.length && i < types.length; i++) {
            if (INVENTORY_TYPE_SLOTS[i] == rawSlot) {
                return types[i];
            }
        }
        return null;
    }

    private @NotNull InventoryType[] selectableInventoryTypes() {
        return new InventoryType[] {
            InventoryType.NORMAL,
            InventoryType.EQUIPMENT,
            InventoryType.RUNE
        };
    }

    private @NotNull MenuShortcutAction actionForInventoryType(@NotNull InventoryType inventoryType) {
        for (MenuShortcutAction action : MenuShortcutAction.values()) {
            if (action.getInventoryType() == inventoryType) {
                return action;
            }
        }
        return MenuShortcutAction.NONE;
    }

    private @NotNull ItemStack withSelectionGlow(@NotNull ItemStack itemStack) {
        ItemStack glowing = itemStack.clone();
        ItemMeta meta = glowing.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            glowing.setItemMeta(meta);
        }
        return glowing;
    }
}
