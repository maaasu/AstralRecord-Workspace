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

public final class SellConfirmScreenView extends BaseMenuScreenView {
    public static final Component CONFIRM_MESSAGE =
        Component.text("\u58f2\u5374\u3057\u307e\u3059\u304b\uff1f", NamedTextColor.YELLOW);
    public static final int PREVIOUS_SLOT = -1;
    public static final int SELL_SLOT = 26;
    public static final int RETURN_TO_SELL_SLOT = 22;
    public static final int NEXT_SLOT = -1;

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

    private @NotNull ItemStack createSummaryItem(@NotNull List<ItemStack> items) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("\u58f2\u5374\u5bfe\u8c61: " + items.size() + " \u4ef6", NamedTextColor.GRAY));
        lore.add(Component.text("\u5408\u8a08\u58f2\u5374\u984d: " + totalSaleValue(items) + GOLD_SUFFIX, NamedTextColor.GOLD));
        lore.add(Component.text("\u58f2\u5374\u3059\u308b\u30a2\u30a4\u30c6\u30e0:", NamedTextColor.YELLOW));
        for (ItemStack itemStack : items) {
            lore.add(Component.text("\u30fb ", NamedTextColor.GRAY).append(displayName(itemStack)));
        }
        return createItem(
            Material.GOLD_INGOT,
            Component.text("\u58f2\u5374\u78ba\u8a8d", NamedTextColor.GOLD),
            lore
        );
    }

    private @NotNull Component displayName(@NotNull ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        return Component.text(itemStack.getType().name(), NamedTextColor.WHITE);
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
