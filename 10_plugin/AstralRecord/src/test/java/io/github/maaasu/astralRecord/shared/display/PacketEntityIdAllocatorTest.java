package io.github.maaasu.astralRecord.shared.display;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketEntityIdAllocatorTest {
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
