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
    boolean afk
) {
    PlayerMetadata withServer(String newServerId, String newChannel) {
        if (newServerId.equalsIgnoreCase(serverId)) {
            return this;
        }
        return new PlayerMetadata(playerId, mcid, newServerId, newChannel, mcid, null, null, false);
    }
}
