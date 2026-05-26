package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * オンライン中のプレイヤー設定スナップショットです。
 */
public final class PlayerSettingSnapshot {
    private final UUID userId;
    private final Map<PlayerSettingKey, PlayerSettingEntry> entries;

    public PlayerSettingSnapshot(
        @NotNull UUID userId,
        @NotNull Map<PlayerSettingKey, PlayerSettingEntry> entries
    ) {
        this.userId = userId;
        this.entries = Collections.unmodifiableMap(new EnumMap<>(entries));
    }

    public @NotNull UUID getUserId() {
        return userId;
    }

    public @NotNull Map<PlayerSettingKey, PlayerSettingEntry> getEntries() {
        return entries;
    }

    public @Nullable PlayerSettingEntry getEntry(@NotNull PlayerSettingKey key) {
        return entries.get(key);
    }

    public @NotNull PlayerSettingSnapshot withEntry(@NotNull PlayerSettingEntry entry) {
        EnumMap<PlayerSettingKey, PlayerSettingEntry> copy = new EnumMap<>(entries);
        copy.put(entry.getSettingKey(), entry);
        return new PlayerSettingSnapshot(userId, copy);
    }
}
