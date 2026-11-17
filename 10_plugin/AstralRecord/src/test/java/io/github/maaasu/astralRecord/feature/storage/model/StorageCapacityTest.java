package io.github.maaasu.astralRecord.feature.storage.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageCapacityTest {

    /**
     * ストレージは拡張トークン未所持時に5ページ、225 entryを上限とする。
     */
    @Test
    void baseCapacityHasFivePagesAnd225Entries() {
        assertEquals(5, StorageCapacity.maxPageCount(0));
        assertEquals(225, StorageCapacity.maxEntryCount(0));
    }

    /**
     * ストレージ拡張トークン1個につき最大ページ数とentry数を1ページ分加算する。
     */
    @Test
    void eachExpansionTokenAddsOnePage() {
        assertEquals(6, StorageCapacity.maxPageCount(1));
        assertEquals(270, StorageCapacity.maxEntryCount(1));
        assertEquals(8, StorageCapacity.maxPageCount(3));
        assertEquals(360, StorageCapacity.maxEntryCount(3));
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
