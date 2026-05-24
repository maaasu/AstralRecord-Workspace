package io.github.maaasu.astralRecord.feature.currency.view;

import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通貨をページング GUI として描画します。
 */
public final class CurrencyGuiView {
    private final PagedGuiView pagedGuiView = new PagedGuiView();

    /**
     * 通貨アイテム一覧を指定ページへ描画します。
     *
     * @param inventory 描画先インベントリ
     * @param items 表示対象通貨アイテム一覧
     * @param pageIndex 0 始まりのページ番号
     */
    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        pagedGuiView.render(inventory, items, pageIndex);
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
}