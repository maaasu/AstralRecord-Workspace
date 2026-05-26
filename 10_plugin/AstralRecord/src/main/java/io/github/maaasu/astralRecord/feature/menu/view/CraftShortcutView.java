package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

final class CraftShortcutView {
    static final int CRAFT_RESULT_RAW_SLOT = 0;
    static final int CRAFT_SHORTCUT_RAW_SLOT_START = 1;

    private final NamespacedKey craftShortcutKey;
    private final NamespacedKey craftActionKey;

    CraftShortcutView(@NotNull NamespacedKey craftShortcutKey, @NotNull NamespacedKey craftActionKey) {
        this.craftShortcutKey = craftShortcutKey;
        this.craftActionKey = craftActionKey;
    }

    @NotNull ItemStack createCraftResultIcon() {
        ItemStack itemStack = createItem(
            Material.KNOWLEDGE_BOOK,
            Component.text("メニュー", NamedTextColor.GREEN),
            List.of(Component.text("クリックしてメニューを開く", NamedTextColor.GRAY))
        );
        markCraftShortcutIcon(itemStack, -1, MenuShortcutAction.MAIN_MENU);
        return itemStack;
    }

    /**
     * クラフト枠ショートカットを描画します。同等の表示内容なら更新を抑止し、
     * 表示中のインベントリ種別と一致するショートカットには発光を付与します。
     *
     * @param player          描画対象プレイヤー
     * @param settings        ショートカット設定
     * @param selectedType    現在表示中のインベントリ種別（指定なしなら null）
     */
    void renderCraftShortcuts(
        @NotNull Player player,
        @NotNull MenuShortcutSettings settings,
        @Nullable InventoryType selectedType
    ) {
        if (!(player.getOpenInventory().getTopInventory() instanceof CraftingInventory inventory)) {
            return;
        }
        if (!canOverwriteCraftMatrix(inventory)) {
            return;
        }

        ItemStack[] currentMatrix = inventory.getMatrix();
        ItemStack[] newMatrix = new ItemStack[MenuShortcutSettings.SLOT_COUNT];
        boolean matrixChanged = false;
        for (int slot = 0; slot < MenuShortcutSettings.SLOT_COUNT; slot++) {
            MenuShortcutAction action = settings.getAction(slot);
            boolean selected = action.getInventoryType() != null && action.getInventoryType() == selectedType;
            newMatrix[slot] = createCraftShortcutIcon(slot, action, selected, selectedType);
            ItemStack existing = currentMatrix != null && slot < currentMatrix.length ? currentMatrix[slot] : null;
            if (!isSameDisplayItem(existing, newMatrix[slot])) {
                matrixChanged = true;
            }
        }

        ItemStack newResult = createCraftResultIcon();
        boolean resultChanged = !isSameDisplayItem(inventory.getResult(), newResult);

        if (!matrixChanged && !resultChanged) {
            return;
        }

        inventory.setMatrix(newMatrix);
        inventory.setResult(newResult);
        player.updateInventory();
    }

    void clearCraftShortcuts(@NotNull Player player) {
        if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory inventory) {
            clearCraftShortcuts(inventory);
            player.updateInventory();
        }
    }

    void clearCraftShortcuts(@NotNull CraftingInventory inventory) {
        if (!isShortcutMatrix(inventory)) {
            return;
        }
        inventory.setMatrix(new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        inventory.setResult(new ItemStack(Material.AIR));
    }

    void removeCraftShortcutItems(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        removeCraftShortcutItems(inventory);
        if (isCraftShortcutIcon(player.getItemOnCursor())) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
        }
        player.updateInventory();
    }

    int getCraftShortcutIndex(int rawSlot) {
        int index = rawSlot - CRAFT_SHORTCUT_RAW_SLOT_START;
        return index >= 0 && index < MenuShortcutSettings.SLOT_COUNT ? index : -1;
    }

    boolean isCraftShortcutIcon(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(craftShortcutKey, PersistentDataType.INTEGER);
    }

    private @NotNull ItemStack createCraftShortcutIcon(
        int shortcutSlotIndex,
        @NotNull MenuShortcutAction action,
        boolean selected,
        @Nullable InventoryType selectedType
    ) {
        if (action == MenuShortcutAction.INVENTORY_CYCLE) {
            return createInventoryCycleShortcutIcon(shortcutSlotIndex, selectedType);
        }
        ItemStack itemStack = createItem(
            action.getMaterial(),
            Component.text(action.getDisplayNameJa(), action.getColor()),
            List.of(Component.text("クリックして実行", NamedTextColor.GRAY))
        );
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, action);
        if (selected) {
            applySelectionGlow(itemStack);
        }
        return itemStack;
    }

    private @NotNull ItemStack createInventoryCycleShortcutIcon(
        int shortcutSlotIndex,
        @Nullable InventoryType selectedType
    ) {
        String currentLabel = selectedType != null ? selectedType.getDisplayNameJa() : InventoryType.NORMAL.getDisplayNameJa();
        ItemStack itemStack = createItem(
            Material.CHEST,
            Component.text("インベントリ切替", NamedTextColor.YELLOW),
            List.of(
                Component.text("現在: " + currentLabel, NamedTextColor.GRAY),
                Component.text("ノーマル → 装備 → ルーン", NamedTextColor.GRAY)
            )
        );
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, MenuShortcutAction.INVENTORY_CYCLE);
        return itemStack;
    }

    private void markCraftShortcutIcon(
        @NotNull ItemStack itemStack,
        int shortcutSlotIndex,
        @NotNull MenuShortcutAction action
    ) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(craftShortcutKey, PersistentDataType.INTEGER, shortcutSlotIndex);
            meta.getPersistentDataContainer().set(craftActionKey, PersistentDataType.STRING, action.getCode());
            itemStack.setItemMeta(meta);
        }
    }

    private void applySelectionGlow(@NotNull ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            itemStack.setItemMeta(meta);
        }
    }

    private boolean canOverwriteCraftMatrix(@NotNull CraftingInventory inventory) {
        for (ItemStack itemStack : inventory.getMatrix()) {
            if (itemStack != null && itemStack.getType() != Material.AIR && !isCraftShortcutIcon(itemStack)) {
                return false;
            }
        }
        return true;
    }

    private boolean isShortcutMatrix(@NotNull CraftingInventory inventory) {
        for (ItemStack itemStack : inventory.getMatrix()) {
            if (itemStack != null && itemStack.getType() != Material.AIR && !isCraftShortcutIcon(itemStack)) {
                return false;
            }
        }
        return true;
    }

    private void removeCraftShortcutItems(@NotNull Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isCraftShortcutIcon(inventory.getItem(slot))) {
                inventory.setItem(slot, new ItemStack(Material.AIR));
            }
        }
    }

    private boolean isSameDisplayItem(@Nullable ItemStack current, @Nullable ItemStack next) {
        boolean currentEmpty = current == null || current.getType() == Material.AIR;
        boolean nextEmpty = next == null || next.getType() == Material.AIR;
        if (currentEmpty || nextEmpty) {
            return currentEmpty == nextEmpty;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}
