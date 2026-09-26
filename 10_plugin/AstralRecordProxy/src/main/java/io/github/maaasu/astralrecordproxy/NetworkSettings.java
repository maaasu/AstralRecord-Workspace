package io.github.maaasu.astralrecordproxy;

import java.util.List;
import java.util.UUID;

/** Proxyが接続制御に使うManagement DB由来のネットワーク設定です。 */
interface NetworkSettings {
    String lobbyServer();

    List<String> gameServers();

    long transferCooldownSeconds();

    long tabRefreshSeconds();

    long presenceHeartbeatSeconds();

    String channelName(String serverId);

    boolean isGameServer(String serverId);

    boolean isDiscordSourceServerExcluded(String serverId);

    boolean isServerAuthority(UUID playerId);

    ProxyConfig.ServerCapacity capacity(String serverId);

    /** 指定チャンネルをVIP限定としてロビーへ案内するか返します。 */
    default boolean donorOnly(String serverId) { return false; }
}
