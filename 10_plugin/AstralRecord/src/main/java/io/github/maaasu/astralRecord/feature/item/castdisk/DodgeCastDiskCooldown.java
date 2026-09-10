package io.github.maaasu.astralRecord.feature.item.castdisk;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ドッジキャストディスクのプレイヤー共有クールダウンを保持します。 */
final class DodgeCastDiskCooldown {
    private final Map<UUID, Long> expiresAtByPlayer = new ConcurrentHashMap<>();

    boolean isActive(@NotNull UUID playerId, long nowMs) {
        return nowMs < expiresAtByPlayer.getOrDefault(playerId, 0L);
    }

    void start(@NotNull UUID playerId, long nowMs) {
        expiresAtByPlayer.put(playerId, nowMs + CastDiskUseService.DODGE_COOLDOWN_MS);
    }

    void clear(@NotNull UUID playerId) {
        expiresAtByPlayer.remove(playerId);
    }
}
