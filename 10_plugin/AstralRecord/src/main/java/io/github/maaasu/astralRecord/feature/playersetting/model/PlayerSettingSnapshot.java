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

    /**
     * 初回ロード時の空の設定を含め、指定された内容を不変スナップショットにします。
     * @param userId 設定を所有するユーザーUUID
     * @param entries 型変換済みの設定。保存済み項目がなければ空
     */
    public PlayerSettingSnapshot(
        @NotNull UUID userId,
        @NotNull Map<PlayerSettingKey, PlayerSettingEntry> entries
    ) {
        this.userId = userId;
        EnumMap<PlayerSettingKey, PlayerSettingEntry> copy = new EnumMap<>(PlayerSettingKey.class);
        copy.putAll(entries);
        this.entries = Collections.unmodifiableMap(copy);
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

    /**
     * 現在のスナップショットを変更せず、設定を一件追加または置換します。
     * @param entry 反映する設定
     * @return 空の初期値からの補完にも対応する新しいスナップショット
     */
    public @NotNull PlayerSettingSnapshot withEntry(@NotNull PlayerSettingEntry entry) {
        EnumMap<PlayerSettingKey, PlayerSettingEntry> copy = new EnumMap<>(PlayerSettingKey.class);
        copy.putAll(entries);
        copy.put(entry.getSettingKey(), entry);
        return new PlayerSettingSnapshot(userId, copy);
    }
}
