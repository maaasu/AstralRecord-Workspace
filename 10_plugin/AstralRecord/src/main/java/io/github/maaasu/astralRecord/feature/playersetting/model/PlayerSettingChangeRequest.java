package io.github.maaasu.astralRecord.feature.playersetting.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * プレイヤー設定変更要求です。
 */
public record PlayerSettingChangeRequest(
    @NotNull UUID userId,
    @NotNull PlayerSettingKey settingKey,
    @NotNull Object newValue,
    @NotNull UUID requestedBy
) {
}
