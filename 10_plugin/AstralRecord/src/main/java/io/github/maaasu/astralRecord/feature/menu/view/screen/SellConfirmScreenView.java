package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.sell.view.SellScreenView;
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

public final class SellConfirmScreenView extends BaseMenuScreenView {
    public static final Component CONFIRM_MESSAGE =
        Component.text("\u58f2\u5374\u3057\u307e\u3059\u304b\uff1f", NamedTextColor.YELLOW);
    public static final int PREVIOUS_SLOT = -1;
    public static final int SELL_SLOT = 26;
    public static final int RETURN_TO_SELL_SLOT = 22;
    public static final int NEXT_SLOT = -1;

    private static final List<Integer> CONTENT_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25);
    private static final String GOLD_SUFFIX = " \u30b4\u30fc\u30eb\u30c9";

    private final NamespacedKey contentPlaceholderKey;
    private final ItemReferenceResolver itemReferenceResolver;

    public SellConfirmScreenView(@NotNull NamespacedKey contentPlaceholderKey, @NotNull ItemService itemService) {
        this.contentPlaceholderKey = contentPlaceholderKey;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(4, createSummaryItem(items));
        renderSellItems(inventory, items);
        inventory.setItem(RETURN_TO_SELL_SLOT, createItem(
            Material.SPECTRAL_ARROW,
            Component.text("\u623b\u308b", NamedTextColor.WHITE),
            List.of(Component.text("\u58f2\u5374GUI\u3078\u623b\u308a\u307e\u3059\u3002", NamedTextColor.GRAY))
        ));
        inventory.setItem(SELL_SLOT, createItem(
            Material.EMERALD,
            Component.text("\u58f2\u5374\u3059\u308b", NamedTextColor.GREEN),
            List.of(
                Component.text("\u8868\u793a\u4e2d\u306e\u30a2\u30a4\u30c6\u30e0\u3092\u58f2\u5374\u3057\u307e\u3059\u3002", NamedTextColor.GRAY),
                Component.text("\u5408\u8a08\u58f2\u5374\u984d: " + totalSaleValue(items) + GOLD_SUFFIX, NamedTextColor.GOLD)
            )
        ));
    }

    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.GRAY_STAINED_GLASS_PANE || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(contentPlaceholderKey, PersistentDataType.INTEGER);
    }

    private void renderSellItems(@NotNull Inventory inventory, @NotNull List<ItemStack> items) {
        int displayCount = Math.min(items.size(), CONTENT_SLOTS.size());
        boolean overflow = items.size() > CONTENT_SLOTS.size();
        if (overflow) {
            displayCount = CONTENT_SLOTS.size() - 1;
        }
        for (int index = 0; index < displayCount; index++) {
            inventory.setItem(CONTENT_SLOTS.get(index), cloneWithSaleLore(items.get(index)));
        }
        if (overflow) {
            int remaining = items.size() - displayCount;
            inventory.setItem(CONTENT_SLOTS.get(CONTENT_SLOTS.size() - 1), createItem(
                Material.PAPER,
                Component.text("\u6b8b\u308a\u306e\u30a2\u30a4\u30c6\u30e0", NamedTextColor.YELLOW),
                List.of(Component.text("\u4ed6 " + remaining + " \u4ef6\u306f\u58f2\u5374GUI\u5074\u3067\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044\u3002", NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack createSummaryItem(@NotNull List<ItemStack> items) {
        return createItem(
            Material.GOLD_INGOT,
            Component.text("\u58f2\u5374\u78ba\u8a8d", NamedTextColor.GOLD),
            List.of(
                Component.text("\u58f2\u5374\u5bfe\u8c61: " + items.size() + " \u4ef6", NamedTextColor.GRAY),
                Component.text("\u5408\u8a08\u58f2\u5374\u984d: " + totalSaleValue(items) + GOLD_SUFFIX, NamedTextColor.GOLD)
            )
        );
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
        lore.add(Component.text(SellScreenView.UNIT_PRICE_LORE_PREFIX + unitPrice + GOLD_SUFFIX, NamedTextColor.YELLOW));
        lore.add(Component.text(
            SellScreenView.TOTAL_PRICE_LORE_PREFIX + ((long) unitPrice * Math.max(1, itemStack.getAmount())) + GOLD_SUFFIX,
            NamedTextColor.GOLD
        ));
        meta.lore(lore);
        displayItem.setItemMeta(meta);
        return displayItem;
    }

    private int unitSaleValue(@NotNull ItemStack itemStack) {
        ItemModel model = itemReferenceResolver.resolveItemModel(itemStack);
        if (model == null || model.getUnSellable()) {
            return 0;
        }
        return Math.max(0, model.getSaleValue());
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

    private void fill(@NotNull Inventory inventory, @NotNull Material material) {
        ItemStack itemStack = createItem(material, Component.text(" "), List.of());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(contentPlaceholderKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, itemStack);
        }
    }
}
