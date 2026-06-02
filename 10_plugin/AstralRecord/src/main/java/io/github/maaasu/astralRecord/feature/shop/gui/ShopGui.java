package io.github.maaasu.astralRecord.feature.shop.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class ShopGui {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    public static final int LIST_SIZE = 54;
    public static final int CONFIRM_SIZE = 27;
    public static final int MAX_LOGICAL_SLOT = 26;
    public static final int BACK_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int ITEM_PREVIEW_SLOT = 13;
    public static final int QUANTITY_MINUS_10_SLOT = 9;
    public static final int QUANTITY_MINUS_1_SLOT = 10;
    public static final int QUANTITY_PLUS_1_SLOT = 16;
    public static final int QUANTITY_PLUS_10_SLOT = 17;
    public static final int CONFIRM_BACK_SLOT = 18;
    public static final int BUY_SLOT = 22;

    private final ShopService shopService;
    private final ItemStackFactory itemStackFactory;
    private final NamespacedKey entryIdKey;

    public ShopGui(
        @NotNull AstralRecord plugin,
        @NotNull ShopService shopService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.shopService = shopService;
        this.itemStackFactory = itemStackFactory;
        this.entryIdKey = new NamespacedKey(plugin, "shop_entry_id");
    }

    public void openList(@NotNull Player player, @NotNull ShopDefinition shop) {
        Inventory inventory = Bukkit.createInventory(
            new ListHolder(shop.id()),
            LIST_SIZE,
            LEGACY_SERIALIZER.deserialize(ColorCodeUtil.translateAlternateColorCodes(shop.name()))
        );
        fillFrame(inventory);
        for (ShopEntry entry : shop.entries()) {
            int guiSlot = toGuiSlot(entry);
            if (guiSlot < 0) {
                continue;
            }
            ItemModel model = shopService.resolveItem(entry);
            if (model == null) {
                continue;
            }
            inventory.setItem(guiSlot, createShopItem(model, entry));
        }
        inventory.setItem(BACK_SLOT, actionItem(Material.SPECTRAL_ARROW, "Back", List.of("Open menu")));
        inventory.setItem(CLOSE_SLOT, actionItem(Material.BARRIER, "Close", List.of()));
        player.openInventory(inventory);
    }

    public void openConfirm(
        @NotNull Player player,
        @NotNull ShopDefinition shop,
        @NotNull ShopEntry entry,
        int quantity,
        @NotNull ShopPurchasePreview preview
    ) {
        Inventory inventory = Bukkit.createInventory(
            new ConfirmHolder(shop.id(), entry.id(), preview.quantity()),
            CONFIRM_SIZE,
            LEGACY_SERIALIZER.deserialize(ColorCodeUtil.translateAlternateColorCodes(shop.name() + " &7/ Buy"))
        );
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        ItemModel model = shopService.resolveItem(entry);
        if (model != null) {
            inventory.setItem(ITEM_PREVIEW_SLOT, itemStackFactory.createShopDisplay(model, Math.max(1, entry.amount()) * preview.quantity()));
        }
        inventory.setItem(QUANTITY_MINUS_10_SLOT, actionItem(Material.REDSTONE, "-10", List.of("Quantity: " + preview.quantity())));
        inventory.setItem(QUANTITY_MINUS_1_SLOT, actionItem(Material.REDSTONE_TORCH, "-1", List.of("Quantity: " + preview.quantity())));
        inventory.setItem(QUANTITY_PLUS_1_SLOT, actionItem(Material.LIME_DYE, "+1", List.of("Quantity: " + preview.quantity())));
        inventory.setItem(QUANTITY_PLUS_10_SLOT, actionItem(Material.EMERALD, "+10", List.of("Quantity: " + preview.quantity())));
        inventory.setItem(CONFIRM_BACK_SLOT, actionItem(Material.SPECTRAL_ARROW, "Back", List.of("Return to list")));
        inventory.setItem(BUY_SLOT, buyItem(entry, preview));
        player.openInventory(inventory);
    }

    public boolean isListInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ListHolder;
    }

    public boolean isConfirmInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ConfirmHolder;
    }

    public @Nullable String getShopId(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        if (inventory.getHolder() instanceof ListHolder holder) {
            return holder.shopId();
        }
        if (inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.shopId();
        }
        return null;
    }

    public @Nullable String getEntryId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.entryId();
        }
        return null;
    }

    public int getQuantity(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.quantity();
        }
        return 1;
    }

    public @Nullable String getEntryId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(entryIdKey, PersistentDataType.STRING);
    }

    private @NotNull ItemStack createShopItem(@NotNull ItemModel model, @NotNull ShopEntry entry) {
        ItemStack itemStack = itemStackFactory.createShopDisplay(model, Math.max(1, entry.amount()));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text("Price: " + shopService.resolveGoldCost(entry) + " gold", NamedTextColor.GOLD));
        for (ShopCostItem cost : shopService.resolveRequiredItems(entry)) {
            lore.add(Component.text("Need: " + cost.itemId() + " x" + cost.amount(), NamedTextColor.GRAY));
        }
        lore.add(Component.text("Click to buy", NamedTextColor.GREEN));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(entryIdKey, PersistentDataType.STRING, entry.id());
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @NotNull ItemStack buyItem(@NotNull ShopEntry entry, @NotNull ShopPurchasePreview preview) {
        Material material = preview.canPurchase() ? Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA;
        List<String> lore = new ArrayList<>();
        lore.add("Item: " + entry.itemId() + " x" + (Math.max(1, entry.amount()) * preview.quantity()));
        lore.add("Quantity: " + preview.quantity());
        lore.add("Gold: " + preview.requiredGold() + " / owned " + preview.ownedGold());
        for (ShopCostItem cost : preview.requiredItems()) {
            lore.add("Consume: " + cost.itemId() + " x" + cost.amount());
        }
        if (!preview.canPurchase()) {
            lore.add("");
            lore.add("Missing:");
            for (ShopCostItem missing : preview.missingItems()) {
                lore.add("- " + missing.itemId() + " x" + missing.amount());
            }
        } else {
            lore.add("");
            lore.add("Click to purchase.");
        }
        return actionItem(material, preview.canPurchase() ? "Purchase" : "Not enough materials", lore);
    }

    private int toGuiSlot(@NotNull ShopEntry entry) {
        Integer logicalSlot = entry.slot();
        if (logicalSlot == null && entry.row() != null && entry.column() != null) {
            logicalSlot = (entry.row() - 1) * 7 + (entry.column() - 1);
        }
        if (logicalSlot == null || logicalSlot < 0 || logicalSlot > MAX_LOGICAL_SLOT) {
            return -1;
        }
        int row = logicalSlot / 7;
        int column = logicalSlot % 7;
        return row * 9 + column + 1;
    }

    private void fillFrame(@NotNull Inventory inventory) {
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        for (int logical = 0; logical <= MAX_LOGICAL_SLOT; logical++) {
            int row = logical / 7;
            int column = logical % 7;
            inventory.setItem(row * 9 + column + 1, new ItemStack(Material.AIR));
        }
    }

    private void fill(@NotNull Inventory inventory, @NotNull Material material) {
        ItemStack filler = actionItem(material, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull String name, @NotNull List<String> lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public record ListHolder(@NotNull String shopId) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, LIST_SIZE);
        }
    }

    public record ConfirmHolder(@NotNull String shopId, @NotNull String entryId, int quantity) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, CONFIRM_SIZE);
        }
    }
}
