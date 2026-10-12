package io.github.maaasu.astralrecordproxy;

import java.util.UUID;

record PlayerMetadata(
    UUID playerId,
    String mcid,
    String serverId,
    String channel,
    String displayName,
    Integer level,
    String className,
    boolean afk,
    int permission,
    boolean classLevelMax,
    boolean accountNameOnly,
    VipTier vipTier,
    Integer playerLevel
) {
    /** プレイヤーレベル未送信の旧metadataでは、PLVを未取得として扱う。 */
    PlayerMetadata(UUID playerId, String mcid, String serverId, String channel, String displayName,
                   Integer level, String className, boolean afk, int permission,
                   boolean classLevelMax, boolean accountNameOnly, VipTier vipTier) {
        this(playerId, mcid, serverId, channel, displayName, level, className, afk, permission,
            classLevelMax, accountNameOnly, vipTier, null);
    }
    /** VIP情報のない旧metadataは通常アカウントとして扱う。 */
    PlayerMetadata(UUID playerId, String mcid, String serverId, String channel, String displayName,
                   Integer level, String className, boolean afk, int permission,
                   boolean classLevelMax, boolean accountNameOnly) {
        this(playerId, mcid, serverId, channel, displayName, level, className, afk, permission,
            classLevelMax, accountNameOnly, VipTier.NONE);
    }
    PlayerMetadata withServer(String newServerId, String newChannel) {
        if (newServerId.equalsIgnoreCase(serverId)) {
            return this;
        }
        return new PlayerMetadata(
            playerId, mcid, newServerId, newChannel, mcid, null, null, false, 0, false, false);
    }
}
