package io.github.maaasu.astralRecord.feature.item.castdisk;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** プレイヤーごとの最新ドッジキャスト予約だけを有効にする世代管理です。 */
final class DodgeCastDiskReservation {
    private final Map<UUID, Long> generationByPlayer = new ConcurrentHashMap<>();

    long replace(@NotNull UUID playerId) {
        return generationByPlayer.merge(playerId, 1L, Long::sum);
    }

    boolean consumeIfCurrent(@NotNull UUID playerId, long generation) {
        return generationByPlayer.remove(playerId, generation);
    }

    void clear(@NotNull UUID playerId) {
        generationByPlayer.remove(playerId);
    }
}
