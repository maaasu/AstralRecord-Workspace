package io.github.maaasu.astralRecord.shared.display;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * クライアント専用の packet entity ID をプラグイン全体で一意に採番します。
 */
public final class PacketEntityIdAllocator {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);

    private PacketEntityIdAllocator() {
    }

    /**
     * 次に利用可能な packet entity ID を返します。
     *
     * @return プラグイン内で重複しない packet entity ID
     */
    public static int nextEntityId() {
        return NEXT_ENTITY_ID.getAndIncrement();
    }
}
