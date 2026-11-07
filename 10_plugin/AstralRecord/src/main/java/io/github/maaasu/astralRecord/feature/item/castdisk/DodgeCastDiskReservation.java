package io.github.maaasu.astralRecord.feature.item.castdisk;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** プレイヤーごとの最新ドッジキャスト予約だけを有効にする世代管理です。 */
final class DodgeCastDiskReservation {
    private final AtomicLong nextGeneration = new AtomicLong();
    private final Map<UUID, Long> generationByPlayer = new ConcurrentHashMap<>();

    /**
     * 取消・消費済みの予約とも重複しない世代で最新予約を登録します。
     * @param playerId 予約対象の Bukkit UUID
     * @return この予約だけを識別する世代
     */
    long replace(@NotNull UUID playerId) {
        long generation = nextGeneration.incrementAndGet();
        generationByPlayer.put(playerId, generation);
        return generation;
    }

    boolean consumeIfCurrent(@NotNull UUID playerId, long generation) {
        return generationByPlayer.remove(playerId, generation);
    }

    void clear(@NotNull UUID playerId) {
        generationByPlayer.remove(playerId);
    }
}
