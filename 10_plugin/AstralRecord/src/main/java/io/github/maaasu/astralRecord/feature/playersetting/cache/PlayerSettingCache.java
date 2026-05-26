package io.github.maaasu.astralRecord.feature.playersetting.cache;

import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * プレイヤー設定スナップショットのオンラインキャッシュです。
 */
public final class PlayerSettingCache {
    private final ConcurrentMap<UUID, PlayerSettingSnapshot> snapshots = new ConcurrentHashMap<>();

    public void put(@NotNull PlayerSettingSnapshot snapshot) {
        snapshots.put(snapshot.getUserId(), snapshot);
    }

    public @Nullable PlayerSettingSnapshot find(@NotNull UUID userId) {
        return snapshots.get(userId);
    }

    public void remove(@NotNull UUID userId) {
        snapshots.remove(userId);
    }

    public void clear() {
        snapshots.clear();
    }
}
