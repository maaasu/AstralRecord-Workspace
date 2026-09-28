package io.github.maaasu.astralRecord.feature.spawner.service;

import org.jetbrains.annotations.Nullable;

/** 管理者に送るモブスポナー表示の種類です。 */
public enum MobSpawnerVisualMode {
    NORMAL((byte) 0),
    LIGHT((byte) 1),
    OFF((byte) 2);

    private final byte storedValue;

    MobSpawnerVisualMode(byte storedValue) {
        this.storedValue = storedValue;
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
