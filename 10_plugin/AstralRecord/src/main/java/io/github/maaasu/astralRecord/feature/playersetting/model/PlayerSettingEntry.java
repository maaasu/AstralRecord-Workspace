package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * スナップショット内のプレイヤー設定 1 件です。
 */
public final class PlayerSettingEntry {
    private final UUID userSettingId;
    private final PlayerSettingKey settingKey;
    private final Object value;
    private final Integer version;

    public PlayerSettingEntry(
        @Nullable UUID userSettingId,
        @NotNull PlayerSettingKey settingKey,
        @NotNull Object value,
        @Nullable Integer version
    ) {
        this.userSettingId = userSettingId;
        this.settingKey = settingKey;
        this.value = value;
        this.version = version;
    }

    public @Nullable UUID getUserSettingId() {
        return userSettingId;
    }

    public @NotNull PlayerSettingKey getSettingKey() {
        return settingKey;
    }

    public @NotNull Object getValue() {
        return value;
    }

    public @Nullable Integer getVersion() {
        return version;
    }
}
