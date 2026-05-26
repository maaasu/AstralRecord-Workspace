package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API 上のプレイヤー設定 1 レコードです。
 */
public final class PlayerSettingModel {
    private final UUID userSettingId;
    private final UUID userId;
    private final String settingKey;
    private final String settingValueJson;
    private final int version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final UUID createdBy;
    private final UUID updatedBy;
    private final boolean isDeleted;

    public PlayerSettingModel(
        @NotNull UUID userSettingId,
        @NotNull UUID userId,
        @NotNull String settingKey,
        @NotNull String settingValueJson,
        int version,
        @NotNull LocalDateTime createdAt,
        @NotNull LocalDateTime updatedAt,
        @NotNull UUID createdBy,
        @NotNull UUID updatedBy,
        boolean isDeleted
    ) {
        this.userSettingId = userSettingId;
        this.userId = userId;
        this.settingKey = settingKey;
        this.settingValueJson = settingValueJson;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.isDeleted = isDeleted;
    }

    public @NotNull UUID getUserSettingId() {
        return userSettingId;
    }

    public @NotNull UUID getUserId() {
        return userId;
    }

    public @NotNull String getSettingKey() {
        return settingKey;
    }

    public @NotNull String getSettingValueJson() {
        return settingValueJson;
    }

    public int getVersion() {
        return version;
    }

    public @NotNull LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public @NotNull LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public @NotNull UUID getCreatedBy() {
        return createdBy;
    }

    public @NotNull UUID getUpdatedBy() {
        return updatedBy;
    }

    public boolean isDeleted() {
        return isDeleted;
    }
}
