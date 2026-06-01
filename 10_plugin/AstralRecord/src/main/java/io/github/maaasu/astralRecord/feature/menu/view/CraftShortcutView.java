package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    void renderCraftShortcuts(
        @NotNull Player player,
        @NotNull MenuShortcutSettings settings,
        @Nullable InventoryType selectedType,
        @Nullable StatusSnapshot snapshot,
        @NotNull AccountModel selectedAccount,
        @NotNull List<AccountModel> accounts
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
            newMatrix[slot] = createCraftShortcutIcon(player, slot, action, selected, selectedType, snapshot, selectedAccount, accounts);
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
        @NotNull Player player,
        int shortcutSlotIndex,
        @NotNull MenuShortcutAction action,
        boolean selected,
        @Nullable InventoryType selectedType,
        @Nullable StatusSnapshot snapshot,
        @NotNull AccountModel selectedAccount,
        @NotNull List<AccountModel> accounts
    ) {
        if (action == MenuShortcutAction.INVENTORY_CYCLE) {
            return createUserInfoDummyIcon(player, shortcutSlotIndex, selectedType, selectedAccount, accounts);
        }
        if (action == MenuShortcutAction.STATUS) {
            return createStatusShortcutIcon(shortcutSlotIndex, snapshot, selectedAccount);
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

    private @NotNull ItemStack createStatusShortcutIcon(
        int shortcutSlotIndex,
        @Nullable StatusSnapshot snapshot,
        @NotNull AccountModel selectedAccount
    ) {
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
        lore.add(Component.text("━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        if (snapshot != null && !snapshot.getValues().isEmpty()) {
            addStatusLine(lore, snapshot, StatusType.ATTACK, NamedTextColor.RED);
            addStatusLine(lore, snapshot, StatusType.MELEE_ATTACK, NamedTextColor.RED);
            addStatusLine(lore, snapshot, StatusType.RANGED_ATTACK, NamedTextColor.RED);
            addStatusLine(lore, snapshot, StatusType.MAGIC_ATTACK, NamedTextColor.RED);
            addStatusLine(lore, snapshot, StatusType.DEFENSE, NamedTextColor.BLUE);
            addStatusLine(lore, snapshot, StatusType.MAGIC_DEFENSE, NamedTextColor.BLUE);
        } else {
            lore.add(Component.text("ステータス未取得", NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.text("━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("クリックして開く", NamedTextColor.YELLOW));
        ItemStack itemStack = createItem(
            MenuIconDefinition.ACCOUNT_INFO.getMaterial(),
            Component.text(MenuIconDefinition.ACCOUNT_INFO.getDisplayNameJa(), MenuIconDefinition.ACCOUNT_INFO.getColor()),
            lore
        );
        applySelectionGlow(itemStack);
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, MenuShortcutAction.STATUS);
        return itemStack;
    }

    private void addStatusLine(
        @NotNull List<Component> lore,
        @NotNull StatusSnapshot snapshot,
        @NotNull StatusType statusType,
        @NotNull NamedTextColor color
    ) {
        if (snapshot.getValue(statusType) == null) {
            return;
        }
        lore.add(Component.text(statusType.getDisplayName() + ": ", color)
            .append(Component.text(statusType.formatValue(snapshot.getMaxValue(statusType)), NamedTextColor.WHITE)));
    }

    private @NotNull ItemStack createUserInfoDummyIcon(
        @NotNull Player player,
        int shortcutSlotIndex,
        @Nullable InventoryType selectedType,
        @NotNull AccountModel selectedAccount,
        @NotNull List<AccountModel> accounts
    ) {
        String currentLabel = selectedType != null ? selectedType.getDisplayNameJa() : InventoryType.NORMAL.getDisplayNameJa();
        String unselectedNames = accounts.stream()
            .filter(account -> !account.getUuid().equals(selectedAccount.getUuid()))
            .map(AccountModel::getAccountName)
            .collect(Collectors.joining(", "));
        if (unselectedNames.isBlank()) {
            unselectedNames = "なし";
        }

        ItemStack itemStack = createItem(
            MenuIconDefinition.ACCOUNT_INFO.getMaterial(),
            Component.text("ユーザ情報", NamedTextColor.YELLOW),
            List.of(
                Component.text("現在: ", NamedTextColor.GRAY).append(Component.text(currentLabel, NamedTextColor.WHITE)),
                Component.text("選択中アカウント: ", NamedTextColor.GRAY)
                    .append(Component.text(selectedAccount.getAccountName(), NamedTextColor.GOLD)),
                Component.text("未選択アカウント: ", NamedTextColor.GRAY)
                    .append(Component.text(unselectedNames, NamedTextColor.WHITE)),
                Component.text("左クリックでインベントリ切替", NamedTextColor.GRAY)
            )
        );
        ItemMeta rawMeta = itemStack.getItemMeta();
        if (rawMeta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            itemStack.setItemMeta(skullMeta);
        }
        markCraftShortcutIcon(itemStack, shortcutSlotIndex, MenuShortcutAction.NONE);
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
