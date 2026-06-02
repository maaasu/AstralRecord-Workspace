package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class SellScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = TrashScreenView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = TrashScreenView.PREVIOUS_SLOT;
    public static final int GUIDE_SLOT = TrashScreenView.GUIDE_SLOT;
    public static final int NEXT_SLOT = TrashScreenView.NEXT_SLOT;
    public static final int CLOSE_SLOT = TrashScreenView.CLOSE_SLOT;
    public static final String UNIT_PRICE_LORE_PREFIX = "売値: ";
    public static final String TOTAL_PRICE_LORE_PREFIX = "合計売値: ";

    private final NamespacedKey contentPlaceholderKey;
    private final ItemReferenceResolver itemReferenceResolver;

    public SellScreenView(@NotNull NamespacedKey contentPlaceholderKey, @NotNull ItemService itemService) {
        this.contentPlaceholderKey = contentPlaceholderKey;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderItems(inventory, items, normalizedPage);
        renderNavigation(inventory, items.size(), normalizedPage, totalSaleValue(items));
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return Math.clamp(pageIndex, 0, totalPages(itemCount) - 1);
    }

    public int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) CONTENT_SLOT_COUNT));
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pageIndex + 1 < totalPages(itemCount);
    }

    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.GRAY_STAINED_GLASS_PANE || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(contentPlaceholderKey, PersistentDataType.INTEGER);
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < CONTENT_SLOT_COUNT; slot++) {
            inventory.setItem(slot, createContentPlaceholder());
        }
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderItems(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, items.size());
        for (int i = start; i < end; i++) {
            ItemStack itemStack = items.get(i);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                inventory.setItem(i - start, cloneWithSaleLore(itemStack));
            }
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex, long totalSaleValue) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.PAPER,
            Component.text("売却ガイド", NamedTextColor.YELLOW),
            List.of(
                Component.text("左クリック: 1 個を売却枠へ移動", NamedTextColor.GRAY),
                Component.text("Shift+左クリック: まとめて売却枠へ移動", NamedTextColor.GRAY),
                Component.text("右クリック: 分割して売却枠へ移動", NamedTextColor.GRAY),
                Component.text("合計売値: " + totalSaleValue + " ゴールド", NamedTextColor.GOLD),
                Component.text("中央の確認ボタンで売却確認を開きます", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(CLOSE_SLOT, createItem(
            Material.GOLD_INGOT,
            Component.text("確認へ", NamedTextColor.GOLD),
            List.of(Component.text("売却前の確認画面を開きます", NamedTextColor.GRAY))
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack cloneWithSaleLore(@NotNull ItemStack itemStack) {
        ItemStack displayItem = cloneWithAmountLore(itemStack);
        ItemMeta meta = displayItem.getItemMeta();
        if (meta == null) {
            return displayItem;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null
            ? new ArrayList<>(meta.lore())
            : new ArrayList<>();
        int unitPrice = unitSaleValue(itemStack);
        lore.add(Component.text(UNIT_PRICE_LORE_PREFIX + unitPrice + " ゴールド", NamedTextColor.YELLOW));
        lore.add(Component.text(TOTAL_PRICE_LORE_PREFIX + ((long) unitPrice * Math.max(1, itemStack.getAmount())) + " ゴールド", NamedTextColor.GOLD));
        meta.lore(lore);
        displayItem.setItemMeta(meta);
        return displayItem;
    }

    private long totalSaleValue(@NotNull List<ItemStack> items) {
        long total = 0L;
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += (long) unitSaleValue(itemStack) * Math.max(1, itemStack.getAmount());
        }
        return total;
    }

    private int unitSaleValue(@NotNull ItemStack itemStack) {
        ItemModel model = itemReferenceResolver.resolveItemModel(itemStack);
        if (model == null || model.getUnSellable()) {
            return 0;
        }
        return Math.max(0, model.getSaleValue());
    }

    private @NotNull ItemStack createContentPlaceholder() {
        ItemStack itemStack = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(contentPlaceholderKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}
