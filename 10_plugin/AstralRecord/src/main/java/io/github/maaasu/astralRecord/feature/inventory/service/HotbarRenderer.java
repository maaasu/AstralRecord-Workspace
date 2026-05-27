package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * HOTBAR 表示と GUI 中ショートカット表示を描画します。
 */
final class HotbarRenderer {
    static final int SHORTCUT_CLOSE_SLOT = 4;
    static final int SHORTCUT_INVENTORY_CYCLE_SLOT = 8;

    private final InventoryItemStackResolver itemStackResolver;

    HotbarRenderer(@NotNull InventoryItemStackResolver itemStackResolver) {
        this.itemStackResolver = itemStackResolver;
    }

    void renderHotbarInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull Map<Integer, InventoryEntryModel> entries,
        @Nullable Integer selectedSlot
    ) {
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        boolean changed = false;
        for (int dbSlot = HotbarLayout.DB_SLOT_START; dbSlot <= HotbarLayout.DB_SLOT_END; dbSlot++) {
            InventoryEntryModel entry = entries.get(dbSlot);
            ItemStack itemStack = entry == null ? createHotbarDummyItem(dbSlot) : itemStackResolver.resolve(entry);
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                itemStack = createHotbarDummyItem(dbSlot);
            }
            if (selectedSlot != null && selectedSlot == dbSlot) {
                itemStack = withSelectionGlow(itemStack);
            }
            changed |= setStorageItemIfChanged(inventory, HotbarLayout.toBukkitSlot(dbSlot), itemStack);
        }
        changed |= setStorageItemIfChanged(inventory, SHORTCUT_INVENTORY_CYCLE_SLOT,
            createInventoryCycleShortcutIcon(InventoryType.NORMAL));

        InventoryEntryModel offhandEntry = entries.get(HotbarLayout.DB_SLOT_OFFHAND);
        ItemStack offhandStack = offhandEntry == null
            ? createHotbarDummyItem(HotbarLayout.DB_SLOT_OFFHAND)
            : itemStackResolver.resolve(offhandEntry);
        if (offhandStack == null || offhandStack.getType() == Material.AIR) {
            offhandStack = createHotbarDummyItem(HotbarLayout.DB_SLOT_OFFHAND);
        }
        if (selectedSlot != null && selectedSlot == HotbarLayout.DB_SLOT_OFFHAND) {
            offhandStack = withSelectionGlow(offhandStack);
        }
        if (!isSameItemStack(inventory.getItemInOffHand(), offhandStack)) {
            inventory.setItemInOffHand(offhandStack);
            changed = true;
        }
        if (changed) {
            astPlayer.getBukkit().updateInventory();
        }
    }

    void renderShortcutIcons(@NotNull AstPlayer astPlayer, @NotNull InventoryType displayed) {
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        boolean changed = false;

        for (int i = 0; i <= 8; i++) {
            if (i == SHORTCUT_CLOSE_SLOT) {
                changed |= setStorageItemIfChanged(inventory, i, createCloseShortcutIcon());
                continue;
            }
            if (i == SHORTCUT_INVENTORY_CYCLE_SLOT) {
                changed |= setStorageItemIfChanged(inventory, i, createInventoryCycleShortcutIcon(displayed));
                continue;
            }
            changed |= setStorageItemIfChanged(inventory, i, createHotbarSpacerIcon());
        }

        ItemStack offhandDummy = createHotbarDummyItem(HotbarLayout.DB_SLOT_OFFHAND);
        if (!isSameItemStack(inventory.getItemInOffHand(), offhandDummy)) {
            inventory.setItemInOffHand(offhandDummy);
            changed = true;
        }
        if (changed) {
            astPlayer.getBukkit().updateInventory();
        }
    }

    private @NotNull ItemStack createInventoryCycleShortcutIcon(@NotNull InventoryType currentDisplayed) {
        Material material = switch (currentDisplayed) {
            case EQUIPMENT -> Material.IRON_CHESTPLATE;
            case RUNE -> Material.AMETHYST_SHARD;
            default -> Material.CHEST;
        };
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(ColorCodeUtil.YELLOW + "インベントリ切替"));
            meta.lore(List.of(
                Component.text(ColorCodeUtil.GRAY + "現在: " + currentDisplayed.getDisplayNameJa()),
                Component.text(ColorCodeUtil.GRAY + "クリックで次へ切替")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createHotbarSpacerIcon() {
        ItemStack itemStack = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createCloseShortcutIcon() {
        ItemStack itemStack = new ItemStack(Material.BARRIER);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(ColorCodeUtil.RED + "閉じる"));
            meta.lore(List.of(Component.text(ColorCodeUtil.GRAY + "GUI を閉じる")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createHotbarDummyItem(int dbSlot) {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            String label = HotbarLayout.isOffhandSlot(dbSlot)
                ? ColorCodeUtil.GRAY + "オフハンドスロット"
                : ColorCodeUtil.GRAY + "ホットバースロット[" + dbSlot + "]";
            meta.displayName(Component.text(label));
            meta.lore(List.of(Component.text(ColorCodeUtil.GRAY + "アイテム未選択")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
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

    private boolean setStorageItemIfChanged(
        @NotNull PlayerInventory inventory,
        int bukkitSlot,
        @Nullable ItemStack itemStack
    ) {
        ItemStack next = itemOrAir(itemStack);
        ItemStack current = inventory.getItem(bukkitSlot);
        if (isSameItemStack(current, next)) {
            return false;
        }
        inventory.setItem(bukkitSlot, next);
        return true;
    }

    private boolean isSameItemStack(@Nullable ItemStack current, @Nullable ItemStack next) {
        boolean currentEmpty = current == null || current.getType() == Material.AIR;
        boolean nextEmpty = next == null || next.getType() == Material.AIR;
        if (currentEmpty || nextEmpty) {
            return currentEmpty == nextEmpty;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }

    private @NotNull ItemStack itemOrAir(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return new ItemStack(Material.AIR);
        }
        return itemStack;
    }
}
