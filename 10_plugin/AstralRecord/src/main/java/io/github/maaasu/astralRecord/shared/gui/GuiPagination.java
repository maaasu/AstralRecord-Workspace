package io.github.maaasu.astralRecord.shared.gui;

/**
 * GUI ページング計算の共通処理を提供します。
 */
public final class GuiPagination {
    private GuiPagination() {
    }

    /**
     * 指定件数と 1 ページ件数から、最低 1 の総ページ数を返します。
     *
     * @param itemCount 表示対象件数
     * @param pageSize 1 ページあたりの件数
     * @return 総ページ数
     * @throws IllegalArgumentException pageSize が 0 以下の場合
     */
    public static int totalPages(int itemCount, int pageSize) {
        validatePageSize(pageSize);
        int safeItemCount = Math.max(0, itemCount);
        return Math.max(1, (safeItemCount + pageSize - 1) / pageSize);
    }

    /**
     * ページ番号を有効範囲へ丸めます。
     *
     * @param pageIndex 補正前ページ番号
     * @param itemCount 表示対象件数
     * @param pageSize 1 ページあたりの件数
     * @return 補正後ページ番号
     */
    public static int normalizePage(int pageIndex, int itemCount, int pageSize) {
        int lastPage = totalPages(itemCount, pageSize) - 1;
        return Math.max(0, Math.min(pageIndex, lastPage));
    }

    /**
     * 前ページが存在するかを返します。
     *
     * @param pageIndex 現在ページ番号
     * @return 前ページが存在するなら true
     */
    public static boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    /**
     * 次ページが存在するかを返します。
     *
     * @param pageIndex 現在ページ番号
     * @param itemCount 表示対象件数
     * @param pageSize 1 ページあたりの件数
     * @return 次ページが存在するなら true
     */
    public static boolean hasNextPage(int pageIndex, int itemCount, int pageSize) {
        return pageIndex + 1 < totalPages(itemCount, pageSize);
    }

    /**
     * ページ先頭のリスト index を返します。
     *
     * @param pageIndex ページ番号
     * @param pageSize 1 ページあたりの件数
     * @return 先頭 index
     */
    public static int pageStart(int pageIndex, int pageSize) {
        validatePageSize(pageSize);
        return Math.max(0, pageIndex) * pageSize;
    }

    /**
     * ページ終端の排他的 index を返します。
     *
     * @param pageIndex ページ番号
     * @param itemCount 表示対象件数
     * @param pageSize 1 ページあたりの件数
     * @return 終端の排他的 index
     */
    public static int pageEnd(int pageIndex, int itemCount, int pageSize) {
        return Math.min(pageStart(pageIndex, pageSize) + pageSize, Math.max(0, itemCount));
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
    }
}
