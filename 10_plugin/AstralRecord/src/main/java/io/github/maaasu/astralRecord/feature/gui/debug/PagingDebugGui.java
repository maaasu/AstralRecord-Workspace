package io.github.maaasu.astralRecord.feature.gui.debug;

import io.github.maaasu.astralRecord.feature.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ページング操作を確認するための一時的なダミー GUI。
 */
public final class PagingDebugGui {
    private static final int DUMMY_ITEM_COUNT = 96;
    private final PagedGuiView pagedGuiView = new PagedGuiView();

    /**
     * ダミーページング GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 0 始まりのページ番号
     */
    public void open(@NotNull Player player, int pageIndex) {
        List<ItemStack> items = createDummyItems();
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, items.size());
        int totalPages = pagedGuiView.totalPages(items.size());
        Inventory inventory = Bukkit.createInventory(
            new Holder(normalizedPage),
            PagedGuiView.SIZE,
            Component.text("ページング確認 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.AQUA)
        );
        pagedGuiView.render(inventory, items, normalizedPage);
        player.openInventory(inventory);
    }

    /**
     * 指定インベントリがページング確認 GUI か判定します。
     *
     * @param inventory 判定対象
     * @return ページング確認 GUI の場合 true
     */
    public boolean isDebugInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * 指定インベントリに紐づくページ番号を返します。
     *
     * @param inventory 判定対象
     * @return 0 始まりのページ番号
     */
    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    /**
     * 前ページへ移動できるか判定します。
     *
     * @param pageIndex 0 始まりのページ番号
     * @return 前ページが存在する場合 true
     */
    public boolean hasPreviousPage(int pageIndex) {
        return pagedGuiView.hasPreviousPage(pageIndex);
    }

    /**
     * 次ページへ移動できるか判定します。
     *
     * @param pageIndex 0 始まりのページ番号
     * @return 次ページが存在する場合 true
     */
    public boolean hasNextPage(int pageIndex) {
        return pagedGuiView.hasNextPage(pageIndex, DUMMY_ITEM_COUNT);
    }

    private @NotNull List<ItemStack> createDummyItems() {
        List<ItemStack> items = new ArrayList<>(DUMMY_ITEM_COUNT);
        for (int i = 1; i <= DUMMY_ITEM_COUNT; i++) {
            items.add(createDummyItem(i));
        }
        return items;
    }

    private @NotNull ItemStack createDummyItem(int number) {
        ItemStack itemStack = new ItemStack(Material.PAPER);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Dummy " + number, NamedTextColor.WHITE));
            meta.lore(List.of(Component.text("ページング確認用", NamedTextColor.GRAY)));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private record Holder(int pageIndex) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}
