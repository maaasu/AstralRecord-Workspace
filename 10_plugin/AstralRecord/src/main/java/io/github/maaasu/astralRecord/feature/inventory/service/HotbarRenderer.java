package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * HOTBAR の割当アイテムを描画します。
 */
final class HotbarRenderer {
    private static final NamespacedKey HOTBAR_DUMMY_KEY =
        new NamespacedKey("astralrecord", "hotbar_dummy");

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
        var accountId = astPlayer.getAccount().getUuid();
        boolean changed = false;
        for (int dbSlot = HotbarLayout.DB_SLOT_START; dbSlot <= HotbarLayout.DB_SLOT_END; dbSlot++) {
            InventoryEntryModel entry = entries.get(dbSlot);
            ItemStack itemStack = entry == null
                ? createHotbarDummyItem(dbSlot)
                : itemStackResolver.resolve(entry, accountId);
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                itemStack = createHotbarDummyItem(dbSlot);
            }
            if (selectedSlot != null && selectedSlot == dbSlot) {
                itemStack = withSelectionGlow(itemStack);
            }
            changed |= setStorageItemIfChanged(inventory, HotbarLayout.toBukkitSlot(dbSlot), itemStack);
        }
        InventoryEntryModel offhandEntry = entries.get(HotbarLayout.DB_SLOT_OFFHAND);
        ItemStack currentOffhand = inventory.getItemInOffHand();
        ItemStack offhandStack = offhandEntry == null
            ? isEmptyOrDummy(currentOffhand)
                ? createHotbarDummyItem(HotbarLayout.DB_SLOT_OFFHAND)
                : currentOffhand
            : itemStackResolver.resolve(offhandEntry, accountId);
        if (offhandStack == null || offhandStack.getType() == Material.AIR) {
            offhandStack = createHotbarDummyItem(HotbarLayout.DB_SLOT_OFFHAND);
        }
        if (selectedSlot != null
            && selectedSlot == HotbarLayout.DB_SLOT_OFFHAND
            && (offhandEntry != null || isHotbarDummy(offhandStack))) {
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

    private @NotNull ItemStack createHotbarDummyItem(int dbSlot) {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(HOTBAR_DUMMY_KEY, PersistentDataType.INTEGER, 1);
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

    static boolean isHotbarDummy(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.getPersistentDataContainer().has(HOTBAR_DUMMY_KEY, PersistentDataType.INTEGER)) {
            return true;
        }
        return itemStack.getType() == Material.GRAY_STAINED_GLASS_PANE
            && Component.text(ColorCodeUtil.GRAY + "オフハンドスロット").equals(meta.displayName())
            && List.of(Component.text(ColorCodeUtil.GRAY + "アイテム未選択")).equals(meta.lore());
    }

    private boolean isEmptyOrDummy(@Nullable ItemStack itemStack) {
        return itemStack == null
            || itemStack.getType() == Material.AIR
            || isHotbarDummy(itemStack);
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
