package io.github.maaasu.astralRecord.feature.network;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Network API が返す、RPGチャンネル単位の接続・運営ロール判定です。 */
public record NetworkChannelAccess(
    @NotNull UUID userUuid,
    @NotNull String serverId,
    boolean channelKnown,
    boolean authority,
    boolean debugUser,
    boolean whitelisted,
    boolean whitelistEnabled,
    boolean allowed,
    int permission
) {
}
