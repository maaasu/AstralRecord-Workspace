package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
        ItemStack itemStack = MenuIconFactory.create(MenuIconDefinition.MAIN_MENU);
        markCraftShortcutIcon(itemStack, -1, MenuShortcutAction.MAIN_MENU);
        return itemStack;
    }

    void renderCraftShortcuts(
        @NotNull Player player,
        @NotNull MenuShortcutSettings settings,
        @NotNull PlayerGuiRenderContext context
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
            newMatrix[slot] = createCraftShortcutIcon(
                slot,
                action,
                context
            );
            ItemStack existing = slot < currentMatrix.length ? currentMatrix[slot] : null;
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
        @NotNull PlayerGuiRenderContext context
    ) {
        if (action == MenuShortcutAction.NONE) {
            return new ItemStack(Material.AIR);
        }
        if (action == MenuShortcutAction.STATUS) {
            return createStatusShortcutIcon(shortcutSlotIndex, context);
        }
        if (action.isCurrencyAction()) {
            return createCurrencyShortcutIcon(shortcutSlotIndex, action, context);
        }
        if (action == MenuShortcutAction.EQUIPMENT_GUI) {
            return createEquipmentShortcutIcon(shortcutSlotIndex, action, context);
        }
        List<Component> lore = new ArrayList<>();
        if (action == MenuShortcutAction.RETURN_TO_BASE) {
            lore.addAll(MenuIconFactory.returnToBaseDetails(context));
        }
        lore.add(action == MenuShortcutAction.MAIN_MENU
            ? MenuIconFactory.openHint()
            : MenuIconFactory.executeHint());
        ItemStack itemStack = MenuIconFactory.create(action.getIconDefinition(), lore);
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, action);
        return itemStack;
    }

    private @NotNull ItemStack createStatusShortcutIcon(
        int shortcutSlotIndex,
        @NotNull PlayerGuiRenderContext context
    ) {
        var selectedAccount = context.account();
        StatusSnapshot snapshot = context.statusSnapshot();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("選択中のアカウント", NamedTextColor.DARK_GRAY));
        lore.add(Component.text(selectedAccount.getAccountName(), NamedTextColor.GOLD)
            .append(Component.text("  Lv.", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(selectedAccount.getLevel()), NamedTextColor.YELLOW)));
        lore.add(Component.text("━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("アカウントスロット: ", NamedTextColor.GRAY)
            .append(Component.text("#" + selectedAccount.getSlotIndex(), NamedTextColor.AQUA)));
        lore.add(Component.text("モード: ", NamedTextColor.GRAY)
            .append(Component.text(selectedAccount.getMode().getDisplayName(), NamedTextColor.LIGHT_PURPLE)));
        lore.add(Component.text("累計経験値: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(selectedAccount.getTotalExperience()), NamedTextColor.GREEN)));
        lore.add(Component.text("CP: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(context.availableClassPoints()), NamedTextColor.AQUA))
            .append(Component.text(" / PP: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(context.availablePassivePoints()), NamedTextColor.AQUA)));
        lore.add(Component.text("━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        if (!snapshot.getValues().isEmpty()) {
            for (StatusType statusType : StatusType.values()) {
                addStatusLine(lore, snapshot, statusType);
            }
        } else {
            lore.add(Component.text("ステータス未取得", NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.text("━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        lore.add(MenuIconFactory.openHint());
        ItemStack itemStack = MenuIconFactory.create(MenuIconDefinition.ACCOUNT_INFO, lore);
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, MenuShortcutAction.STATUS);
        return itemStack;
    }

    private @NotNull ItemStack createCurrencyShortcutIcon(
        int shortcutSlotIndex,
        @NotNull MenuShortcutAction action,
        @NotNull PlayerGuiRenderContext context
    ) {
        List<Component> lore = new ArrayList<>(MenuIconFactory.currencyDetails(context));
        lore.add(MenuIconFactory.openHint());
        ItemStack itemStack = MenuIconFactory.create(action.getIconDefinition(), lore);
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, action);
        return itemStack;
    }

    private @NotNull ItemStack createEquipmentShortcutIcon(
        int shortcutSlotIndex,
        @NotNull MenuShortcutAction action,
        @NotNull PlayerGuiRenderContext context
    ) {
        List<Component> lore = new ArrayList<>(MenuIconFactory.equipmentDetails(context));
        lore.add(MenuIconFactory.openHint());
        ItemStack itemStack = MenuIconFactory.create(action.getIconDefinition(), lore);
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, action);
        return itemStack;
    }

    private void addStatusLine(
        @NotNull List<Component> lore,
        @NotNull StatusSnapshot snapshot,
        @NotNull StatusType statusType
    ) {
        lore.add(Component.text(statusType.getDisplayName() + ": ", statusType.namedColor())
            .append(Component.text(statusType.formatValue(snapshot.getMaxValue(statusType)), NamedTextColor.WHITE)));
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

}
