package io.github.maaasu.astralRecord.feature.storage.view;

import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortDirection;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortKey;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemRarity;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
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

    /**
     * フィルター候補一覧を描画します。
     *
     * @param inventory 候補を表示するインベントリ
     * @param filterType 候補種別
     * @param selectedValue 現在選択中の値
     */
    public void renderFilterOptions(
        @NotNull Inventory inventory,
        @NotNull FilterType filterType,
        @Nullable String selectedValue
    ) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of()));
        }
        List<FilterOption> options = filterType.options();
        for (int index = 0; index < options.size() && index < inventory.getSize(); index++) {
            FilterOption option = options.get(index);
            NamedTextColor color = option.value() == null ? NamedTextColor.WHITE : filterType.color();
            List<Component> lore = option.value() != null && option.value().equalsIgnoreCase(selectedValue)
                ? List.of(Component.text("現在選択中", NamedTextColor.GREEN))
                : List.of(Component.text("クリックで適用", NamedTextColor.GRAY));
            inventory.setItem(index, createItem(option.material(), Component.text(option.label(), color), lore));
        }
    }

    /** ストレージのフィルター候補種別です。 */
    public enum FilterType {
        CATEGORY("カテゴリを選択", NamedTextColor.AQUA) {
            @Override List<FilterOption> options() {
                List<FilterOption> options = new ArrayList<>();
                options.add(new FilterOption(null, "すべて", Material.BARRIER));
                for (ItemCategory category : ItemCategory.values()) {
                    if (category != ItemCategory.UNKNOWN) {
                        options.add(new FilterOption(category.getApiValue(), category.getDisplayNameJa(), Material.CHEST));
                    }
                }
                return options;
            }
        },
        RARITY("レアリティを選択", NamedTextColor.LIGHT_PURPLE) {
            @Override List<FilterOption> options() {
                List<FilterOption> options = new ArrayList<>();
                options.add(new FilterOption(null, "すべて", Material.BARRIER));
                for (String value : ItemRarity.orderedValues()) {
                    options.add(new FilterOption(value, ItemRarity.displayNameJa(value), Material.NETHER_STAR));
                }
                return options;
            }
        },
        SORT_KEY("並び替えを選択", NamedTextColor.YELLOW) {
            @Override List<FilterOption> options() {
                List<FilterOption> options = new ArrayList<>();
                for (StorageSortKey value : StorageSortKey.values()) {
                    options.add(new FilterOption(value.name(), value.getDisplayNameJa(), Material.COMPASS));
                }
                return options;
            }
        },
        SORT_DIRECTION("並び方向を選択", NamedTextColor.GREEN) {
            @Override List<FilterOption> options() {
                List<FilterOption> options = new ArrayList<>();
                for (StorageSortDirection value : StorageSortDirection.values()) {
                    options.add(new FilterOption(value.name(), value.getDisplayNameJa(), Material.ARROW));
                }
                return options;
            }
        };

        private final String title;
        private final NamedTextColor color;

        FilterType(@NotNull String title, @NotNull NamedTextColor color) {
            this.title = title;
            this.color = color;
        }

        /** @return 候補一覧GUIのタイトル */
        public @NotNull String title() { return title; }

        /** @return 候補アイコンの表示色 */
        public @NotNull NamedTextColor color() { return color; }
        abstract List<FilterOption> options();
    }

    private record FilterOption(@Nullable String value, @NotNull String label, @NotNull Material material) {}

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
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        return GuiItems.hasMarker(itemStack, Material.GRAY_STAINED_GLASS_PANE, contentPlaceholderKey);
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
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, entries.size(), CONTENT_SLOT_COUNT);
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
            Component.text("カテゴリフィルター", NamedTextColor.AQUA),
            filterLore("カテゴリ", categoryFilterLabel(options.categoryFilter()))
        ));
        inventory.setItem(RARITY_FILTER_SLOT, createItem(
            Material.NETHER_STAR,
            Component.text("レアリティフィルター", NamedTextColor.LIGHT_PURPLE),
            filterLore("レアリティ", rarityFilterLabel(options.rarityFilter()))
        ));
        inventory.setItem(SORT_KEY_SLOT, createItem(
            Material.COMPASS,
            Component.text("並び替え", NamedTextColor.YELLOW),
            filterLore("並び順", options.sortKey().getDisplayNameJa())
        ));
        inventory.setItem(SORT_DIRECTION_SLOT, createItem(
            Material.ARROW,
            Component.text("方向", NamedTextColor.GREEN),
            List.of(Component.text(options.sortDirection().getDisplayNameJa(), NamedTextColor.WHITE))
        ));
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.PAPER,
            Component.text("ストレージガイド", NamedTextColor.YELLOW),
            List.of(
                Component.text("下の所持品クリックで収納", NamedTextColor.GRAY),
                Component.text("上の一覧クリックで取り出し", NamedTextColor.GRAY),
                Component.text("左: 1個 / 右: 半分", NamedTextColor.GRAY),
                Component.text("Shift+左: 1スタック / Shift+右: 全部", NamedTextColor.GRAY)
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
        ItemStack itemStack = cloneWithStorageAmountLore(entry.itemStack());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
            ? new ArrayList<>(meta.lore())
            : new ArrayList<>();
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

    private @NotNull ItemStack cloneWithStorageAmountLore(@NotNull ItemStack itemStack) {
        ItemStack displayItem = itemStack.clone();
        ItemMeta meta = displayItem.getItemMeta();
        if (meta == null) {
            return displayItem;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
            ? new ArrayList<>(meta.lore())
            : new ArrayList<>();
        lore.add(Component.text(DISPLAY_AMOUNT_LORE_PREFIX + displayItem.getAmount(), NamedTextColor.GRAY));
        meta.lore(lore.stream().map(GuiItems::noItalic).toList());
        displayItem.setItemMeta(meta);
        return displayItem;
    }

    private @NotNull String categoryFilterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : ItemCategory.displayNameJa(value);
    }

    private @NotNull String rarityFilterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : ItemRarity.displayNameJa(value);
    }

    private @NotNull List<Component> filterLore(@NotNull String label, @NotNull String value) {
        return List.of(
            Component.text("◇ " + label, NamedTextColor.DARK_AQUA),
            Component.text("  現在: ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE)),
            Component.text("  クリックで候補一覧を表示", NamedTextColor.DARK_GRAY)
        );
    }

    private @NotNull ItemStack createContentPlaceholder() {
        return GuiItems.placeholder(contentPlaceholderKey);
    }
}
