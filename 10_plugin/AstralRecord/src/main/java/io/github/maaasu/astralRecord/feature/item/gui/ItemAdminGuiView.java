package io.github.maaasu.astralRecord.feature.item.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemAdminViewOptions;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemRarity;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理者用アイテム一覧 GUI の描画を担当します。
 */
public final class ItemAdminGuiView {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int CATEGORY_FILTER_SLOT = 46;
    public static final int RARITY_FILTER_SLOT = 47;
    public static final int GUIDE_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private static final Component TITLE = Component.text("管理アイテム一覧", NamedTextColor.GOLD);

    private final NamespacedKey contentPlaceholderKey;
    private final ItemStackFactory itemStackFactory;

    /**
     * 管理者用アイテム一覧 GUI View を初期化します。
     *
     * @param plugin プラグイン本体
     * @param itemStackFactory 表示用 ItemStack 生成ファクトリ
     */
    public ItemAdminGuiView(
        @NotNull AstralRecord plugin,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.contentPlaceholderKey = new NamespacedKey(plugin, "item_admin_content_placeholder");
        this.itemStackFactory = itemStackFactory;
    }

    /**
     * 管理者用アイテム一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param items 表示対象アイテム一覧
     * @param options 現在のフィルタ状態
     * @param pageIndex 表示ページ
     */
    public void open(
        @NotNull Player player,
        @NotNull List<ItemModel> items,
        @NotNull ItemAdminViewOptions options,
        int pageIndex
    ) {
        Inventory inventory = Bukkit.createInventory(new Holder(null, List.of()), SIZE, TITLE);
        render(inventory, items, options, pageIndex);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 管理者用アイテム一覧のフィルター候補 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param filterType 選択するフィルター種別
     * @param values ロード済みアイテムから取得した候補値
     * @param selectedValue 現在選択中の値
     */
    public void openFilterSelection(
        @NotNull Player player,
        @NotNull FilterType filterType,
        @NotNull List<String> values,
        @Nullable String selectedValue
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(filterType, List.copyOf(values)),
            SIZE,
            Component.text(filterType.title(), filterType.color())
        );
        renderFilterOptions(inventory, filterType, values, selectedValue);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 開いている管理者用アイテム一覧 GUI を再描画します。
     *
     * @param inventory 再描画対象インベントリ
     * @param items 表示対象アイテム一覧
     * @param options 現在のフィルタ状態
     * @param pageIndex 表示ページ
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull List<ItemModel> items,
        @NotNull ItemAdminViewOptions options,
        int pageIndex
    ) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderEntries(inventory, items, normalizedPage);
        renderNavigation(inventory, items.size(), normalizedPage, options);
    }

    /**
     * 指定インベントリが管理者用アイテム一覧 GUI かどうかを返します。
     *
     * @param inventory 判定対象インベントリ
     * @return 管理者用アイテム一覧 GUI なら true
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * 指定 GUI がフィルター候補画面なら種別を返します。
     *
     * @param inventory 判定対象インベントリ
     * @return フィルター種別。一覧画面または対象外なら {@code null}
     */
    public @Nullable FilterType getFilterType(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder
            ? holder.filterType()
            : null;
    }

    /**
     * フィルター候補画面で表示した slot から選択値を解決します。
     *
     * @param inventory フィルター候補 GUI
     * @param rawSlot クリックされた raw slot
     * @return 表示時の候補に対応する選択結果。候補外なら {@code null}
     */
    public @Nullable FilterSelection getFilterSelectionAtSlot(
        @Nullable Inventory inventory,
        int rawSlot
    ) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)
            || holder.filterType() == null
            || rawSlot < 0
            || rawSlot > holder.values().size()) {
            return null;
        }
        String value = rawSlot == 0 ? null : holder.values().get(rawSlot - 1);
        return new FilterSelection(holder.filterType(), value);
    }

    /**
     * ページ番号を有効範囲へ補正します。
     *
     * @param pageIndex 補正前ページ
     * @param itemCount 表示対象件数
     * @return 補正後ページ
     */
    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    /**
     * 総ページ数を返します。
     *
     * @param itemCount 表示対象件数
     * @return 総ページ数
     */
    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    /**
     * 前ページが存在するかを返します。
     *
     * @param pageIndex 現在ページ
     * @return 前ページがあれば true
     */
    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    /**
     * 次ページが存在するかを返します。
     *
     * @param pageIndex 現在ページ
     * @param itemCount 表示対象件数
     * @return 次ページがあれば true
     */
    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    /**
     * コンテンツ未配置プレースホルダーかどうかを返します。
     *
     * @param itemStack 判定対象アイテム
     * @return プレースホルダーなら true
     */
    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        return GuiItems.hasMarker(itemStack, Material.GRAY_STAINED_GLASS_PANE, contentPlaceholderKey);
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

    private void renderEntries(@NotNull Inventory inventory, @NotNull List<ItemModel> items, int pageIndex) {
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, items.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, createEntryIcon(items.get(index)));
        }
    }

    private void renderNavigation(
        @NotNull Inventory inventory,
        int itemCount,
        int pageIndex,
        @NotNull ItemAdminViewOptions options
    ) {
        inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
            Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasPreviousPage(pageIndex)
        ));
        inventory.setItem(CATEGORY_FILTER_SLOT, createItem(
            Material.HOPPER,
            Component.text("カテゴリ", NamedTextColor.AQUA, TextDecoration.BOLD),
            filterLore(categoryFilterLabel(options.categoryFilter()))
        ));
        inventory.setItem(RARITY_FILTER_SLOT, createItem(
            Material.NETHER_STAR,
            Component.text("レア度", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
            filterLore(filterLabel(options.rarityFilter()))
        ));
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.PAPER,
            Component.text("取得ガイド", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(
                Component.text("左クリック: 1個", NamedTextColor.GRAY),
                Component.text("右クリック: 半スタック", NamedTextColor.GRAY),
                Component.text("Shift+左クリック: 1スタック", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
            Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasNextPage(pageIndex, itemCount)
        ));
    }

    private @NotNull ItemStack createEntryIcon(@NotNull ItemModel model) {
        ItemStack itemStack = itemStackFactory.createShopDisplay(model, previewStackAmount(model));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text("管理者配布", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("レア度: " + ItemRarity.displayNameJa(model.getRarity()), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("左=1個 / 右=半スタック / Shift+左=1スタック", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private int previewStackAmount(@NotNull ItemModel model) {
        return Math.clamp(model.getMaxStack(), 1, 64);
    }

    private @NotNull String filterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : ItemRarity.displayNameJa(value);
    }

    private @NotNull String categoryFilterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : ItemCategory.displayNameJa(value);
    }

    private @NotNull List<Component> filterLore(@NotNull String value) {
        return List.of(
            Component.text("現在: " + value, NamedTextColor.WHITE),
            Component.text("クリックで候補一覧を表示", NamedTextColor.DARK_GRAY)
        );
    }

    private void renderFilterOptions(
        @NotNull Inventory inventory,
        @NotNull FilterType filterType,
        @NotNull List<String> values,
        @Nullable String selectedValue
    ) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, spacer);
        }
        inventory.setItem(0, createFilterOption(
            Material.BARRIER,
            "すべて",
            selectedValue == null || selectedValue.isBlank(),
            NamedTextColor.WHITE
        ));
        for (int index = 0; index < values.size() && index + 1 < inventory.getSize(); index++) {
            String value = values.get(index);
            inventory.setItem(index + 1, createFilterOption(
                filterType.material(value),
                filterType.label(value),
                selectedValue != null && selectedValue.equalsIgnoreCase(value),
                filterType.color()
            ));
        }
    }

    private @NotNull ItemStack createFilterOption(
        @NotNull Material material,
        @NotNull String label,
        boolean selected,
        @NotNull NamedTextColor color
    ) {
        return createItem(
            material,
            Component.text(label, color),
            selected
                ? List.of(Component.text("現在選択中", NamedTextColor.GREEN))
                : List.of(Component.text("クリックで適用", NamedTextColor.GRAY))
        );
    }

    private @NotNull ItemStack createContentPlaceholder() {
        return GuiItems.placeholder(contentPlaceholderKey);
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    /** 管理者用アイテム GUI のフィルター種別です。 */
    public enum FilterType {
        CATEGORY("カテゴリを選択", NamedTextColor.AQUA),
        RARITY("レア度を選択", NamedTextColor.LIGHT_PURPLE);

        private final String title;
        private final NamedTextColor color;

        FilterType(@NotNull String title, @NotNull NamedTextColor color) {
            this.title = title;
            this.color = color;
        }

        /** @return 候補一覧 GUI のタイトル */
        public @NotNull String title() {
            return title;
        }

        /** @return 候補アイコンの表示色 */
        public @NotNull NamedTextColor color() {
            return color;
        }

        private @NotNull String label(@NotNull String value) {
            return this == CATEGORY
                ? ItemCategory.displayNameJa(value)
                : ItemRarity.displayNameJa(value);
        }

        private @NotNull Material material(@NotNull String value) {
            if (this == RARITY) {
                return Material.NETHER_STAR;
            }
            return switch (ItemCategory.fromApiValue(value)) {
                case BUNDLE -> Material.BUNDLE;
                case CURRENCY -> Material.GOLD_INGOT;
                case EQUIPMENT -> Material.DIAMOND_CHESTPLATE;
                case MATERIAL -> Material.IRON_INGOT;
                case ORB -> Material.END_CRYSTAL;
                case CONSUMABLE -> Material.APPLE;
                case RUNE -> Material.ENCHANTED_BOOK;
                case SIGIL -> Material.FIREWORK_STAR;
                case UNKNOWN -> Material.BARRIER;
            };
        }
    }

    /**
     * 表示時の slot-to-value 対応を保持するフィルター選択結果です。
     *
     * @param filterType フィルター種別
     * @param value 選択値。「すべて」は {@code null}
     */
    public record FilterSelection(
        @NotNull FilterType filterType,
        @Nullable String value
    ) {
    }

    private record Holder(
        @Nullable FilterType filterType,
        @NotNull List<String> values
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.CHEST, "管理アイテム一覧");
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
