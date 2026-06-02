package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.storage.model.StorageSortDirection;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StorageScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int CATEGORY_FILTER_SLOT = 46;
    public static final int RARITY_FILTER_SLOT = 47;
    public static final int SORT_KEY_SLOT = 48;
    public static final int SORT_DIRECTION_SLOT = 50;
    public static final int GUIDE_SLOT = 51;
    public static final int NEXT_SLOT = 53;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NamespacedKey contentPlaceholderKey;
    private final NamespacedKey entryIdKey;

    public StorageScreenView(@NotNull NamespacedKey contentPlaceholderKey, @NotNull NamespacedKey entryIdKey) {
        this.contentPlaceholderKey = contentPlaceholderKey;
        this.entryIdKey = entryIdKey;
    }

    public void render(
        @NotNull Inventory inventory,
        @NotNull List<StorageViewEntry> entries,
        @NotNull StorageViewOptions options,
        int pageIndex
    ) {
        int normalizedPage = normalizePage(pageIndex, entries.size());
        clear(inventory);
        renderEntries(inventory, entries, normalizedPage);
        renderNavigation(inventory, entries.size(), normalizedPage, options);
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

    public @Nullable UUID getEntryId(@Nullable ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String value = meta.getPersistentDataContainer().get(entryIdKey, PersistentDataType.STRING);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < CONTENT_SLOT_COUNT; slot++) {
            inventory.setItem(slot, createContentPlaceholder());
        }
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
    }

    private void renderEntries(@NotNull Inventory inventory, @NotNull List<StorageViewEntry> entries, int pageIndex) {
        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, entries.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, createEntryIcon(entries.get(index)));
        }
    }

    private void renderNavigation(
        @NotNull Inventory inventory,
        int itemCount,
        int pageIndex,
        @NotNull StorageViewOptions options
    ) {
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(CATEGORY_FILTER_SLOT, createItem(
            Material.HOPPER,
            Component.text("カテゴリ", NamedTextColor.AQUA),
            List.of(Component.text(filterLabel(options.categoryFilter()), NamedTextColor.WHITE))
        ));
        inventory.setItem(RARITY_FILTER_SLOT, createItem(
            Material.NETHER_STAR,
            Component.text("レア度", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text(filterLabel(options.rarityFilter()), NamedTextColor.WHITE))
        ));
        inventory.setItem(SORT_KEY_SLOT, createItem(
            Material.COMPASS,
            Component.text("並び順", NamedTextColor.YELLOW),
            List.of(Component.text(options.sortKey().getDisplayNameJa(), NamedTextColor.WHITE))
        ));
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(SORT_DIRECTION_SLOT, createItem(
            options.sortDirection() == StorageSortDirection.ASC ? Material.ARROW : Material.SPECTRAL_ARROW,
            Component.text("方向", NamedTextColor.GREEN),
            List.of(Component.text(options.sortDirection().getDisplayNameJa(), NamedTextColor.WHITE))
        ));
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.PAPER,
            Component.text("ストレージガイド", NamedTextColor.YELLOW),
            List.of(
                Component.text("下の所持品クリックで収納", NamedTextColor.GRAY),
                Component.text("上の一覧クリックで取り出し", NamedTextColor.GRAY),
                Component.text("左: 1個 / 右: 半分 / Shift: 全部", NamedTextColor.GRAY)
            )
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack createEntryIcon(@NotNull StorageViewEntry entry) {
        ItemStack itemStack = cloneWithAmountLore(entry.itemStack());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
            ? new ArrayList<>(meta.lore())
            : new ArrayList<>();
        lore.add(Component.text("カテゴリ: " + entry.itemModel().getCategory(), NamedTextColor.GRAY));
        lore.add(Component.text("レア度: " + entry.itemModel().getRarity(), NamedTextColor.GRAY));
        lore.add(Component.text("売値: " + Math.max(0, entry.itemModel().getSaleValue()), NamedTextColor.GOLD));
        lore.add(Component.text("獲得: " + DATE_TIME_FORMATTER.format(entry.acquiredAt()), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            entryIdKey,
            PersistentDataType.STRING,
            entry.entry().getInventoryEntryId().toString()
        );
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @NotNull String filterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : value;
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
