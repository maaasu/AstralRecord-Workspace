package io.github.maaasu.astralRecord.shared.gui.paging;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 54 スロット GUI のページング表示とナビゲーションスロットを扱います。
 */
public final class PagedGuiView {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int CLOSE_SLOT = -1;
    public static final int NEXT_SLOT = 53;

    /**
     * 指定ページを GUI へ描画します。
     *
     * @param inventory 描画先インベントリ
     * @param items 表示対象アイテム一覧
     * @param pageIndex 0 始まりのページ番号
     */
    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderPageItems(inventory, items, normalizedPage);
        renderNavigation(inventory, items.size(), normalizedPage);
    }

    /**
     * ページ番号を表示可能範囲へ丸めます。
     *
     * @param pageIndex 0 始まりのページ番号
     * @param itemCount 表示対象アイテム数
     * @return 表示可能なページ番号
     */
    public int normalizePage(int pageIndex, int itemCount) {
        int totalPages = totalPages(itemCount);
        return Math.clamp(pageIndex, 0, totalPages - 1);
    }

    /**
     * 総ページ数を返します。
     *
     * @param itemCount 表示対象アイテム数
     * @return 1 以上の総ページ数
     */
    public int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) CONTENT_SLOT_COUNT));
    }

    /**
     * 前ページへ移動できるか判定します。
     *
     * @param pageIndex 0 始まりのページ番号
     * @return 前ページが存在する場合 true
     */
    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    /**
     * 次ページへ移動できるか判定します。
     *
     * @param pageIndex 0 始まりのページ番号
     * @param itemCount 表示対象アイテム数
     * @return 次ページが存在する場合 true
     */
    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pageIndex + 1 < totalPages(itemCount);
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderPageItems(
        @NotNull Inventory inventory,
        @NotNull List<ItemStack> items,
        int pageIndex
    ) {
        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, items.size());
        for (int i = start; i < end; i++) {
            ItemStack itemStack = items.get(i);
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

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE),
                List.of(Component.text((pageIndex) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, createItem(
            Material.ARROW,
            Component.text("戻る", NamedTextColor.WHITE),
            List.of(Component.text("メニューへ戻る", NamedTextColor.GRAY))
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}