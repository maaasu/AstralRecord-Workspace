package io.github.maaasu.astralRecord.feature.currency.view;

import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通貨をページング GUI として描画します。
 */
public final class CurrencyGuiView {
    public static final int EXCHANGE_SLOT = 51;
    private final PagedGuiView pagedGuiView = new PagedGuiView();

    /**
     * 通貨アイテム一覧を指定ページへ描画します。
     *
     * @param inventory 描画先インベントリ
     * @param items 表示対象通貨アイテム一覧
     * @param pageIndex 0 始まりのページ番号
     * @param exchangeUnlocked カレンシー画面から両替所を開ける場合はtrue
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull List<ItemStack> items,
        int pageIndex,
        boolean exchangeUnlocked
    ) {
        pagedGuiView.render(inventory, items, pageIndex);
        fillEmptySlots(inventory);
        inventory.setItem(EXCHANGE_SLOT, createExchangeIcon(exchangeUnlocked));
    }

    /**
     * ページ番号を表示可能範囲へ丸めます。
     *
     * @param pageIndex 0 始まりのページ番号
     * @param itemCount 表示対象アイテム数
     * @return 表示可能なページ番号
     */
    public int normalizePage(int pageIndex, int itemCount) {
        return pagedGuiView.normalizePage(pageIndex, itemCount);
    }

    /**
     * 総ページ数を返します。
     *
     * @param itemCount 表示対象アイテム数
     * @return 1 以上の総ページ数
     */
    public int totalPages(int itemCount) {
        return pagedGuiView.totalPages(itemCount);
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
     * @param itemCount 表示対象アイテム数
     * @return 次ページが存在する場合 true
     */
    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pagedGuiView.hasNextPage(pageIndex, itemCount);
    }

    private void fillEmptySlots(@NotNull Inventory inventory) {
        ItemStack filler = createFiller();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR) {
                inventory.setItem(slot, filler.clone());
            }
        }
    }

    private @NotNull ItemStack createFiller() {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createExchangeIcon(boolean unlocked) {
        return io.github.maaasu.astralRecord.shared.gui.GuiItems.create(
            Material.EMERALD,
            Component.text(
                unlocked ? "ゴールド両替所" : "両替所は利用できません",
                unlocked ? net.kyori.adventure.text.format.NamedTextColor.GOLD
                    : net.kyori.adventure.text.format.NamedTextColor.RED
            ),
            unlocked
                ? List.of(Component.text("クリックして両替GUIを開きます"))
                : List.of(Component.text("ユグドラシルの星核を所持すると、ここから両替できます"))
        );
    }
}
