package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー設定の既定値を解決します。
 */
public final class PlayerSettingDefaults {

    public @NotNull Object resolveDefault(@NotNull PlayerSettingKey key) {
        return key.getDefaultValue();
    }
}
