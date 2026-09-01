package io.github.maaasu.astralRecord.feature.storage.model;

/**
 * ストレージのページ単位の容量を定義します。
 */
public final class StorageCapacity {
    /** 1ページに表示できるストレージ entry 数です。 */
    public static final int PAGE_SIZE = 45;
    /** 拡張トークン未所持時の最大ページ数です。 */
    public static final int BASE_PAGE_COUNT = 5;

    private StorageCapacity() {
    }

    /**
     * 拡張トークン所持数から最大ページ数を求めます。
     *
     * @param expansionTokenCount ストレージ拡張トークンの所持数
     * @return 最大ページ数
     */
    public static int maxPageCount(long expansionTokenCount) {
        long safeCount = Math.max(0L, expansionTokenCount);
        if (safeCount >= Integer.MAX_VALUE - BASE_PAGE_COUNT) {
            return Integer.MAX_VALUE;
        }
        return BASE_PAGE_COUNT + (int) safeCount;
    }

    /**
     * 拡張トークン所持数から最大 entry 数を求めます。
     *
     * @param expansionTokenCount ストレージ拡張トークンの所持数
     * @return 最大 entry 数
     */
    public static int maxEntryCount(long expansionTokenCount) {
        int pageCount = maxPageCount(expansionTokenCount);
        if (pageCount > Integer.MAX_VALUE / PAGE_SIZE) {
            return Integer.MAX_VALUE;
        }
        return pageCount * PAGE_SIZE;
    }
}
