package io.github.maaasu.astralRecord.feature.item.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemAdminViewOptions;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    public static final int CLOSE_SLOT = 50;
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
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE, TITLE);
        render(inventory, items, options, pageIndex);
        player.openInventory(inventory);
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
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
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
        inventory.setItem(CLOSE_SLOT, createItem(
            Material.BARRIER,
            Component.text("閉じる", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("GUI を閉じます", NamedTextColor.GRAY))
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
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
        lore.add(Component.text("カテゴリ: " + ItemCategory.displayNameJa(model.getCategory()), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("レア度: " + normalizeLabel(model.getRarity()), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
        return value == null || value.isBlank() ? "すべて" : normalizeLabel(value);
    }

    private @NotNull String categoryFilterLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "すべて" : ItemCategory.displayNameJa(value);
    }

    private @NotNull String normalizeLabel(@NotNull String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private @NotNull List<Component> filterLore(@NotNull String value) {
        return List.of(
            Component.text("現在: " + value, NamedTextColor.WHITE),
            Component.text("クリックで切り替え", NamedTextColor.DARK_GRAY)
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

    private record Holder() implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
