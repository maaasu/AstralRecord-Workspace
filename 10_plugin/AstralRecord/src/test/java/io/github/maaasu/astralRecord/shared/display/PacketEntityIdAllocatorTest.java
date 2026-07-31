package io.github.maaasu.astralRecord.shared.display;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketEntityIdAllocatorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 5.5. 共通 packet display 基盤
     * 検証契約: 全feature共有採番器が並行要求にも重複しないvirtual entity IDを返す。
     */
    @Test
    void allocatesUniqueIdsAcrossConcurrentPacketDisplays() {
        int allocationCount = 10_000;
        Set<Integer> allocatedIds = ConcurrentHashMap.newKeySet();

        IntStream.range(0, allocationCount)
            .parallel()
            .forEach(ignored -> allocatedIds.add(PacketEntityIdAllocator.nextEntityId()));

        assertEquals(allocationCount, allocatedIds.size());
    }
}
