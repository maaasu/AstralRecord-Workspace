package io.github.maaasu.astralRecord.feature.storage.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageCapacityTest {

    @Test
    void eachExpansionTokenAddsOnePageToBaseCapacity() {
        assertCapacity(0, 5, 225);
        assertCapacity(1, 6, 270);
        assertCapacity(3, 8, 360);
    }

    private static void assertCapacity(long tokens, int pages, int entries) {
        assertEquals(pages, StorageCapacity.maxPageCount(tokens), "pages with " + tokens + " tokens");
        assertEquals(entries, StorageCapacity.maxEntryCount(tokens), "entries with " + tokens + " tokens");
    }

    /**
     * 不正な負数の拡張トークン数で基礎容量を下回らない。
     */
    @Test
    void negativeTokenCountDoesNotReduceBaseCapacity() {
        assertEquals(5, StorageCapacity.maxPageCount(-1));
        assertEquals(225, StorageCapacity.maxEntryCount(-1));
    }

    /**
     * 拡張トークン数が型上限に近くてもページ数とentry数の計算を負数へ反転させない。
     */
    @Test
    void largeTokenCountSaturatesAtIntegerLimit() {
        assertEquals(Integer.MAX_VALUE, StorageCapacity.maxPageCount(Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, StorageCapacity.maxEntryCount(Long.MAX_VALUE));
    }
}
