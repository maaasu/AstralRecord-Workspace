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
    int permission,
    boolean vip,
    String vipTier,
    java.time.Instant vipExpiresAt,
    boolean donorOnly
) {
    /** 旧応答にはVIP接続権を付与しません。 */
    public NetworkChannelAccess(UUID userUuid, String serverId, boolean channelKnown,
            boolean authority, boolean debugUser, boolean whitelisted, boolean whitelistEnabled,
            boolean allowed, int permission) {
        this(userUuid, serverId, channelKnown, authority, debugUser, whitelisted, whitelistEnabled,
            allowed, permission, false, "NONE", null, false);
    }

    /**
     * 選択中アカウントのVIPが失効していないか確認します。
     * @return APIがVIPと認め、種別・期限も有効ならtrue
     */
    public boolean hasActiveVip() {
        return vip && ("DONER".equals(vipTier) || "ASTRALDER".equals(vipTier))
            && vipExpiresAt != null && vipExpiresAt.isAfter(java.time.Instant.now());
    }
}
