package io.github.maaasu.astralRecord.feature.spawner.service;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 管理者に送る Mob・採集スポナー共通の表示設定です。 */
public enum MobSpawnerVisualMode {
    NORMAL((byte) 0),
    LIGHT((byte) 1),
    OFF((byte) 2);

    private final byte storedValue;

    MobSpawnerVisualMode(byte storedValue) {
        this.storedValue = storedValue;
    }

    /**
     * 既存の保存キーを両スポナー種別で共有します。
     *
     * @param plugin キーの名前空間を所有するプラグイン
     * @return 表示モードの PDC キー
     */
    public static @NotNull NamespacedKey storageKey(@NotNull Plugin plugin) {
        return new NamespacedKey(plugin, "mob_spawner_visual_mode");
    }

    /**
     * プレイヤーデータへ保存する値を返します。
     *
     * @return 表示モードの保存値
     */
    public byte storedValue() {
        return storedValue;
    }

    /**
     * 保存値から表示モードを復元します。未設定または未知の値は通常表示にします。
     *
     * @param value プレイヤーデータの保存値
     * @return 対応する表示モード
     */
    public static MobSpawnerVisualMode fromStoredValue(@Nullable Byte value) {
        if (value != null) {
            for (MobSpawnerVisualMode mode : values()) {
                if (mode.storedValue == value) {
                    return mode;
                }
            }
        }
        return NORMAL;
    }
}
