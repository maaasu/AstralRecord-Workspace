package io.github.maaasu.astralRecord.shared.challenge;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挑戦待機Hubへ到着した直後の離脱入力を一時的に抑止します。
 */
public final class ChallengeWaitingHubArrivalGuard {
    private static final long SUPPRESSION_NANOS = Duration.ofSeconds(2L).toNanos();

    private final Map<UUID, Long> suppressedUntilNanosByPlayer = new ConcurrentHashMap<>();

    /**
     * プレイヤーのHub到着時刻を記録し、以後約2秒間の初期スポーン離脱入力を抑止します。
     *
     * @param playerId Hubへ到着したプレイヤーUUID
     */
    public void markArrival(@NotNull UUID playerId) {
        markArrivalAt(playerId, System.nanoTime());
    }

    /**
     * プレイヤーの初期スポーン離脱入力が現在抑止中か判定します。
     *
     * @param playerId 判定対象プレイヤーUUID
     * @return 到着から2秒未満なら {@code true}
     */
    public boolean isSuppressed(@NotNull UUID playerId) {
        return isSuppressedAt(playerId, System.nanoTime());
    }

    /**
     * プレイヤーの到着抑止状態を破棄します。
     *
     * @param playerId 退出したプレイヤーUUID
     */
    public void clear(@NotNull UUID playerId) {
        suppressedUntilNanosByPlayer.remove(playerId);
    }

    void markArrivalAt(@NotNull UUID playerId, long nowNanos) {
        suppressedUntilNanosByPlayer.put(playerId, nowNanos + SUPPRESSION_NANOS);
    }

    boolean isSuppressedAt(@NotNull UUID playerId, long nowNanos) {
        Long suppressedUntilNanos = suppressedUntilNanosByPlayer.get(playerId);
        if (suppressedUntilNanos == null) {
            return false;
        }
        if (suppressedUntilNanos - nowNanos > 0L) {
            return true;
        }
        suppressedUntilNanosByPlayer.remove(playerId, suppressedUntilNanos);
        return false;
    }
}
