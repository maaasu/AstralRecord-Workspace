package io.github.maaasu.astralRecord.shared.gui.paging;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;

/**
 * 54 スロット GUI のページング表示とナビゲーションスロットを提供します。
 */
public final class PagedGuiView {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    /**
     * 生成済みアイテムの指定ページとナビゲーションを描画します。
     *
     * @param inventory 主スレッドで更新する描画先
     * @param items 全ページの表示アイテム
     * @param pageIndex 有効範囲へ補正するページ番号
     */
    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        render(inventory, items, pageIndex, Function.identity());
    }

    /**
     * 指定ページの最大45件だけを表示アイテムへ変換して描画します。
     * 全件数からページ番号とナビゲーションを決め、生成したアイテムは複製して配置します。
     *
     * @param inventory 主スレッドで更新する描画先
     * @param items 全ページの元データ。描画中は変更しないこと
     * @param pageIndex 有効範囲へ補正するページ番号
     * @param itemFactory 表示範囲の各データに一度だけ適用する変換。nullの結果は空欄にする
     * @param <T> 元データの型
     */
    public <T> void render(
        @NotNull Inventory inventory,
        @NotNull List<T> items,
        int pageIndex,
        @NotNull Function<? super T, ? extends ItemStack> itemFactory
    ) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderPageItems(inventory, items, normalizedPage, itemFactory);
        renderNavigation(inventory, items.size(), normalizedPage);
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

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    /**
     * 指定ページの範囲だけを変換し、描画先の先頭スロットから複製して配置します。
     *
     * @param inventory 主スレッドで更新する描画先
     * @param items 全ページの元データ
     * @param pageIndex 補正済みページ番号
     * @param itemFactory 表示アイテムへの変換。nullの結果は配置しない
     * @param <T> 元データの型
     */
    private <T> void renderPageItems(
        @NotNull Inventory inventory,
        @NotNull List<T> items,
        int pageIndex,
        @NotNull Function<? super T, ? extends ItemStack> itemFactory
    ) {
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, items.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            ItemStack itemStack = itemFactory.apply(items.get(i));
            if (itemStack != null) {
                inventory.setItem(i - start, itemStack.clone());
            }
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
            Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasPreviousPage(pageIndex)
        ));
        inventory.setItem(BACK_SLOT, GuiItems.backButton(new GuiNavigationDestination(
            Material.PLAYER_HEAD,
            "メニュー",
            GuiItems.MAIN_MENU_HEAD_TEXTURE
        )));
        inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
            Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasNextPage(pageIndex, itemCount)
        ));
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }
}
