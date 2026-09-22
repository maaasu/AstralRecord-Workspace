package io.github.maaasu.astralrecordproxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Plugin(id = "astralrecordproxy", name = "AstralRecordProxy", version = "1.0.0")
public final class AstralRecordProxyPlugin {
    static final long SERVER_METRICS_TTL_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long AUTHORITY_TRANSFER_PREPARATION_TTL_MILLIS = TimeUnit.SECONDS.toMillis(10L);
    private static final int DONOR_PERMISSION = 5;
    private static final String LIFECYCLE_ACTION_JOIN = "join";
    private static final String LIFECYCLE_ACTION_CHANNEL_CONNECT = "channel_connect";
    private static final String LIFECYCLE_ACTION_LEAVE = "leave";
    private static final ZoneId JAPAN_ZONE = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter BAN_EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm").withZone(JAPAN_ZONE);
    private static final MinecraftChannelIdentifier CHANNEL =
        MinecraftChannelIdentifier.from(BackendProtocol.CHANNEL);
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, PlayerMetadata> metadata = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGameConnectMillis = new ConcurrentHashMap<>();
    private final Map<UUID, AuthorityTransferPreparation> authorityTransferPreparations = new ConcurrentHashMap<>();
    private final Set<UUID> pendingGameConnections = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> serverReservations = new ConcurrentHashMap<>();
    private final Map<String, ServerMetric> serverMspt = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, PlayerMetadata>> tabDisplayCache = new ConcurrentHashMap<>();
    private final AtomicBoolean discordPollRunning = new AtomicBoolean();
    private final AtomicLong discordSequence = new AtomicLong();
    private final AtomicReference<String> discordGenerationId = new AtomicReference<>();
    private final AtomicReference<ManagedNetworkSettings> managedSettings = new AtomicReference<>();
    private final AtomicBoolean settingsRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean settingsFailureLogged = new AtomicBoolean();
    private ProxyConfig config;
    private NetworkApiClient api;
    private ScheduledTask tabRefreshTask;
    private ScheduledTask presenceHeartbeatTask;

    @Inject
    public AstralRecordProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = ProxyConfig.load(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load AstralRecordProxy config", exception);
        }
        if (config.allowInsecureTls()) {
            logger.warn("Network API TLS certificate and host name verification are disabled. Use only in development.");
        }
        api = new NetworkApiClient(config);
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("server").plugin(this).build(),
            new ServerMenuCommand());
        proxy.getScheduler().buildTask(this, this::pollDiscordChat)
            .repeat(Duration.ofMillis(config.discordPollMillis())).schedule();
        proxy.getScheduler().buildTask(this, this::refreshManagedSettings)
            .repeat(Duration.ofSeconds(config.settingsRefreshSeconds())).schedule();
        refreshManagedSettings();
        logger.info("AstralRecordProxy enabled. Waiting for Management DB network settings.");
    }

    /** APIからManagement DB設定を取得し、初回だけYAML旧設定をbootstrapする。 */
    private void refreshManagedSettings() {
        if (!settingsRefreshRunning.compareAndSet(false, true)) return;
        api.getManagedSettings().handle((settings, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(settings);
            return NetworkApiClient.isNotFound(failure)
                ? api.bootstrapManagedSettings(config.legacySettings())
                : CompletableFuture.<ManagedNetworkSettings>failedFuture(failure);
        }).thenCompose(future -> future).whenComplete((settings, failure) -> {
            settingsRefreshRunning.set(false);
            if (failure != null) {
                if (settingsFailureLogged.compareAndSet(false, true)) {
                    logger.warn("Management DB network settings are unavailable; new logins are denied until settings load.", failure);
                }
                return;
            }
            settingsFailureLogged.set(false);
            ManagedNetworkSettings previous = managedSettings.getAndSet(settings);
            if (previous == null || previous.tabRefreshSeconds() != settings.tabRefreshSeconds()
                || previous.presenceHeartbeatSeconds() != settings.presenceHeartbeatSeconds()) {
                rescheduleManagedTasks(settings);
            }
            refreshActiveBans();
        });
    }

    /** Management DB設定の周期変更を次回実行から反映する。 */
    private void rescheduleManagedTasks(NetworkSettings settings) {
        if (tabRefreshTask != null) tabRefreshTask.cancel();
        if (presenceHeartbeatTask != null) presenceHeartbeatTask.cancel();
        tabRefreshTask = proxy.getScheduler().buildTask(this, this::refreshTabEntries)
            .repeat(Duration.ofSeconds(settings.tabRefreshSeconds())).schedule();
        presenceHeartbeatTask = proxy.getScheduler().buildTask(this, this::refreshPresence)
            .repeat(Duration.ofSeconds(settings.presenceHeartbeatSeconds())).schedule();
    }

    /** Management DBの有効BAN一覧に含まれる接続済みプレイヤーを切断する。 */
    private void refreshActiveBans() {
        api.getActiveBans().whenComplete((bans, failure) -> {
            if (failure != null) {
                logger.debug("Failed to refresh active network bans", failure);
                return;
            }
            Map<UUID, NetworkApiClient.BanState> active = new ConcurrentHashMap<>();
            bans.stream().filter(NetworkApiClient.BanState::active)
                .forEach(ban -> active.put(ban.playerId(), ban));
            proxy.getAllPlayers().forEach(player -> {
                NetworkApiClient.BanState ban = active.get(player.getUniqueId());
                if (ban != null) player.disconnect(banDisconnectReason(
                    ban.indefinite(), ban.expiresAtUtc(), ban.reason(), OffsetDateTime.now()));
            });
        });
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        NetworkSettings settings = settings();
        if (settings != null) proxy.getServer(settings.lobbyServer()).ifPresent(event::setInitialServer);
    }

    /** Proxyログイン時にManagement DBのBAN・ロビー入場可否を確認する。 */
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                Component.text("認証サーバーに接続できません。しばらくしてから再度お試しください。", NamedTextColor.RED)));
            return EventTask.async(() -> { });
        }
        return EventTask.withContinuation(continuation -> api.getAdmission(
            event.getPlayer().getUniqueId(), settings.lobbyServer()).whenComplete((admission, failure) -> {
                if (failure != null) {
                    event.setResult(ResultedEvent.ComponentResult.denied(Component.text(
                        "認証サーバーに接続できません。しばらくしてから再度お試しください。", NamedTextColor.RED)));
                } else if (!admission.admitted()) {
                    event.setResult(ResultedEvent.ComponentResult.denied(admissionDenyComponent(admission)));
                }
                continuation.resume();
            }));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        if (event.getPlayer().getCurrentServer().isPresent()) return;
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!target.equalsIgnoreCase(settings.lobbyServer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                Component.text("認証サーバーに接続できません。", NamedTextColor.RED)));
            return;
        }
        String kickedServer = event.getServer().getServerInfo().getName();
        if (kickedServer.equalsIgnoreCase(settings.lobbyServer())) {
            Component reason = event.getServerKickReason().orElse(
                Component.text("ロビーサーバーへ接続できません。", NamedTextColor.RED));
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
            return;
        }
        proxy.getServer(settings.lobbyServer()).ifPresentOrElse(
            lobby -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                lobby, Component.text("ロビーへ移動しました。", NamedTextColor.YELLOW))),
            () -> event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                Component.text("ロビーサーバーへ接続できません。", NamedTextColor.RED))));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) return;
        Player player = event.getPlayer();
        authorityTransferPreparations.remove(player.getUniqueId());
        String serverId = event.getServer().getServerInfo().getName();
        metadata.compute(player.getUniqueId(), (ignored, current) -> {
            if (current == null) {
                return lobbyMetadata(player, serverId);
            }
            return current.withServer(serverId, settings.channelName(serverId));
        });
        if (settings.isGameServer(serverId)) {
            lastGameConnectMillis.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getUsername();
        String currentServer = event.getPlayer().getCurrentServer()
            .map(connection -> connection.getServerInfo().getName()).orElse(settings.lobbyServer());
        String previousServer = event.getPreviousServer() == null
            ? null : event.getPreviousServer().getServerInfo().getName();
        LifecycleNotification notification = lifecycleNotification(playerName, previousServer, currentServer, settings);
        if (notification != null) {
            api.publishLifecycleMessage(
                currentServer, playerId, playerName, notification.action(), notification.message())
                .exceptionally(failure -> {
                    logger.warn("Failed to publish player lifecycle message", failure);
                    return null;
                });
        }
        if (event.getPreviousServer() != null
            && settings.isGameServer(event.getPreviousServer().getServerInfo().getName())) {
            lastGameConnectMillis.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
        refreshTabEntries();
        proxy.getScheduler().buildTask(this, this::refreshTabEntries)
            .delay(Duration.ofSeconds(1L))
            .schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        NetworkSettings settings = settings();
        if (settings == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        authorityTransferPreparations.remove(playerId);
        String currentServer = event.getPlayer().getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElseGet(() -> {
                PlayerMetadata current = metadata.get(playerId);
                return current == null ? null : current.serverId();
            });
        LifecycleNotification notification = disconnectNotification(
            event.getPlayer().getUsername(), currentServer, settings);
        if (notification != null) {
            api.publishLifecycleMessage(
                currentServer, playerId, event.getPlayer().getUsername(), notification.action(), notification.message())
                .exceptionally(failure -> {
                    logger.warn("Failed to publish player disconnect message", failure);
                    return null;
                });
        }
        boolean disconnectedFromGame = event.getPlayer().getCurrentServer()
            .map(connection -> settings.isGameServer(connection.getServerInfo().getName()))
            .orElseGet(() -> {
                PlayerMetadata current = metadata.get(playerId);
                return current != null && settings.isGameServer(current.serverId());
            });
        if (disconnectedFromGame) {
            lastGameConnectMillis.put(playerId, System.currentTimeMillis());
        }
        metadata.remove(playerId);
        removeDisconnectedTabEntry(playerId);
        api.removePlayer(playerId).exceptionally(failure -> {
            logger.warn("Failed to remove player presence for {}", playerId, failure);
            return null;
        });
    }

    /**
     * 切断者のTabキャッシュとentryを、全体同期と同じ排他境界で削除します。
     *
     * @param playerId 切断したプレイヤーUUID
     */
    private synchronized void removeDisconnectedTabEntry(UUID playerId) {
        tabDisplayCache.remove(playerId);
        tabDisplayCache.values().forEach(cache -> cache.remove(playerId));
        removeTabEntryFromAllViewers(proxy.getAllPlayers(), playerId);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        NetworkSettings settings = settings();
        if (settings == null) return;
        try {
            BackendProtocol.Incoming incoming = BackendProtocol.decode(event.getData());
            if (incoming instanceof BackendProtocol.Connect connect) {
                requestConnectionWithAdmission(connection.getPlayer(), connection.getServerInfo().getName(),
                    connect.targetServer());
            } else if (incoming instanceof BackendProtocol.Metadata update) {
                if (!connection.getPlayer().getUniqueId().equals(update.playerId())) {
                    return;
                }
                String sourceServer = connection.getServerInfo().getName();
                String currentServer = connection.getPlayer().getCurrentServer()
                    .map(current -> current.getServerInfo().getName())
                    .orElse(null);
                if (!isCurrentBackend(currentServer, sourceServer)) {
                    return;
                }
                metadata.put(update.playerId(), new PlayerMetadata(
                    update.playerId(), update.mcid(), sourceServer, update.channel(), update.displayName(),
                    update.level(), update.className(), update.afk(), update.permission(),
                    update.classLevelMax(), update.accountNameOnly()));
                refreshTabEntries();
            } else if (incoming instanceof BackendProtocol.Chat chat) {
                if (!connection.getPlayer().getUniqueId().equals(chat.playerId())) {
                    return;
                }
                String sourceServerId = connection.getServerInfo().getName();
                dispatchMinecraftChat(
                    sourceServerId,
                    settings,
                    () -> broadcastMinecraftChat(chat),
                    () -> api.publishMinecraftChat(chat, sourceServerId).exceptionally(failure -> {
                        logger.warn("Failed to relay Minecraft chat to API", failure);
                        return null;
                    }));
            } else if (incoming instanceof BackendProtocol.ServerMetrics metrics
                && Double.isFinite(metrics.mspt()) && metrics.mspt() >= 0.0D) {
                serverMspt.put(
                    connection.getServerInfo().getName().toLowerCase(Locale.ROOT),
                    new ServerMetric(metrics.mspt(), System.nanoTime()));
            } else if (incoming instanceof BackendProtocol.PrivateChat privateChat
                && connection.getPlayer().getUniqueId().equals(privateChat.playerId())) {
                broadcastPrivateChat(connection.getServerInfo().getName(), privateChat);
            } else if (incoming instanceof BackendProtocol.DirectMessage directMessage
                && connection.getPlayer().getUniqueId().equals(directMessage.playerId())) {
                String sourceServerId = connection.getServerInfo().getName();
                String currentServerId = connection.getPlayer().getCurrentServer()
                    .map(current -> current.getServerInfo().getName())
                    .orElse(null);
                if (!isCurrentBackend(currentServerId, sourceServerId)) {
                    return;
                }
                deliverDirectMessage(sourceServerId, connection.getPlayer(), directMessage);
            }
        } catch (RuntimeException | IOException exception) {
            logger.warn("Rejected malformed AstralRecord plugin message", exception);
        }
    }

    /**
     * MinecraftチャットはProxy内へ常に配信し、Discord中継だけ送信元backend設定で制御する。
     */
    static void dispatchMinecraftChat(
        String sourceServerId,
        NetworkSettings config,
        Runnable minecraftBroadcast,
        Runnable discordRelay
    ) {
        minecraftBroadcast.run();
        if (!config.isDiscordSourceServerExcluded(sourceServerId)) {
            discordRelay.run();
        }
    }

    /**
     * Proxy接続またはbackend切替のDiscord通知本文を生成する。
     *
     * @param playerName プレイヤー名
     * @param previousServer 切替元backend。初回接続ではnull
     * @param currentServer 接続先backend
     * @param config Proxy設定
     * @return 通知本文。通知対象外ならnull
     */
    static String lifecycleMessage(String playerName, String previousServer, String currentServer, NetworkSettings config) {
        LifecycleNotification notification = lifecycleNotification(playerName, previousServer, currentServer, config);
        return notification == null ? null : notification.message();
    }

    /**
     * Proxy接続またはbackend切替のDiscord通知を、本文とアクション種別の組で生成する。
     *
     * @param playerName プレイヤー名
     * @param previousServer 切替元backend。初回接続ではnull
     * @param currentServer 接続先backend
     * @param config Proxy設定
     * @return 通知本文とアクション種別。通知対象外ならnull
     */
    static LifecycleNotification lifecycleNotification(
        String playerName, String previousServer, String currentServer, NetworkSettings config
    ) {
        if (previousServer == null) {
            return currentServer == null ? null
                : new LifecycleNotification(LIFECYCLE_ACTION_JOIN, playerName + "さんがサーバーに参加しました");
        }
        if (currentServer == null || config.isDiscordSourceServerExcluded(currentServer)) return null;
        if (previousServer.equalsIgnoreCase(currentServer)
            || config.isDiscordSourceServerExcluded(previousServer)) return null;
        return new LifecycleNotification(
            LIFECYCLE_ACTION_CHANNEL_CONNECT,
            playerName + "さんが" + config.channelName(previousServer)
                + "から" + config.channelName(currentServer) + "へ接続しました");
    }

    /**
     * Proxyからの実切断のDiscord通知本文を生成する。
     *
     * @param playerName プレイヤー名
     * @param currentServer 切断元backend
     * @param config Proxy設定
     * @return 通知本文。通知対象外ならnull
     */
    static String disconnectMessage(String playerName, String currentServer, NetworkSettings config) {
        LifecycleNotification notification = disconnectNotification(playerName, currentServer, config);
        return notification == null ? null : notification.message();
    }

    /**
     * Proxyからの実切断のDiscord通知を、本文とアクション種別の組で生成する。
     *
     * @param playerName プレイヤー名
     * @param currentServer 切断元backend
     * @param config Proxy設定
     * @return 通知本文とアクション種別。通知対象外ならnull
     */
    static LifecycleNotification disconnectNotification(
        String playerName, String currentServer, NetworkSettings config
    ) {
        if (currentServer == null || config.isDiscordSourceServerExcluded(currentServer)) return null;
        return new LifecycleNotification(LIFECYCLE_ACTION_LEAVE, playerName + "さんがサーバーから退出しました");
    }

    /**
     * Backendから要求されたサーバー接続を検証して実行する。
     *
     * @param player 接続するプレイヤー
     * @param sourceServer 要求元backend名
     * @param targetServer 接続先backend名
     * @param permission LobbyがAPI admissionから取得した権限
     */
    /** backend切替の直前にManagement DBのBAN・チャンネルアクセスを再照会する。 */
    private void requestConnectionWithAdmission(Player player, String sourceServer, String targetServer) {
        api.getAdmission(player.getUniqueId(), targetServer).whenComplete((admission, failure) -> {
            if (failure != null) {
                player.sendMessage(Component.text("認証サーバーに接続できません。しばらくしてから再度お試しください。", NamedTextColor.RED));
                return;
            }
            if (!admission.admitted()) {
                Component reason = admissionDenyComponent(admission);
                if (isBanDeny(admission)) {
                    player.disconnect(reason);
                } else {
                    player.sendMessage(reason);
                }
                return;
            }
            requestConnection(player, sourceServer, targetServer, admission.permission());
        });
    }

    private void requestConnection(Player player, String sourceServer, String targetServer, int permission) {
        NetworkSettings settings = settings();
        if (settings == null) {
            player.sendMessage(Component.text("認証サーバーに接続できません。", NamedTextColor.RED));
            return;
        }
        boolean serverAuthority = settings.isServerAuthority(player.getUniqueId());
        int effectivePermission = serverAuthority ? 99 : permission;
        RegisteredServer target = proxy.getServer(targetServer).orElse(null);
        if (target == null || (!settings.isGameServer(targetServer)
            && !targetServer.equalsIgnoreCase(settings.lobbyServer()))) {
            player.sendMessage(Component.text("接続先サーバーが見つかりません。", NamedTextColor.RED));
            return;
        }
        if (settings.isGameServer(targetServer)) {
            String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
            boolean preparedAuthorityTransfer = !sourceServer.equalsIgnoreCase(settings.lobbyServer())
                && isCurrentBackend(currentServer, sourceServer)
                && consumeAuthorityTransferPreparation(player.getUniqueId(), sourceServer, targetServer);
            if (!canRequestGameServerFrom(
                sourceServer, settings, player.getUniqueId(), preparedAuthorityTransfer)) {
                player.sendMessage(Component.text("RPGサーバーへの接続はロビーから選択してください。", NamedTextColor.YELLOW));
                return;
            }
            boolean currentlyInGame = player.getCurrentServer()
                .map(connection -> settings.isGameServer(connection.getServerInfo().getName()))
                .orElse(false);
            if (shouldRejectCurrentGameConnection(currentlyInGame, preparedAuthorityTransfer)) {
                player.sendMessage(Component.text("RPGサーバーから戻る場合は /lobby を使用してください。", NamedTextColor.YELLOW));
                return;
            }
            long remaining = cooldownRemaining(player.getUniqueId());
            if (remaining > 0L) {
                player.sendMessage(Component.text("再接続まで " + remaining + " 秒お待ちください。", NamedTextColor.YELLOW));
                return;
            }
            if (!pendingGameConnections.add(player.getUniqueId())) {
                player.sendMessage(Component.text("サーバーへ接続中です。", NamedTextColor.YELLOW));
                return;
            }
            ProxyConfig.ServerCapacity capacity = settings.capacity(targetServer);
            int connectionLimit = capacity.limitFor(effectivePermission);
            if (connectionLimit > 0 && !reserveServerSlot(targetServer, target, connectionLimit)) {
                pendingGameConnections.remove(player.getUniqueId());
                player.sendMessage(Component.text("このチャンネルは満員です。", NamedTextColor.YELLOW));
                return;
            }
            connectToGameServer(player, target, targetServer, connectionLimit > 0);
            return;
        }
        player.createConnectionRequest(target).connect().thenAccept(result -> {
            if (!result.isSuccessful()) {
                player.sendMessage(Component.text("サーバーへの接続に失敗しました。", NamedTextColor.RED));
            }
        });
    }

    /**
     * Proxy最高権限ユーザーの/server引数を検証し、安全な接続経路へ渡す。
     *
     * @param player 実行プレイヤー
     * @param requestedTarget 指定された接続先backend名
     */
    private void requestAuthorityServerCommand(Player player, String requestedTarget) {
        NetworkSettings settings = settings();
        if (settings == null) {
            player.sendMessage(Component.text("認証サーバーに接続できません。", NamedTextColor.RED));
            return;
        }
        if (!settings.isServerAuthority(player.getUniqueId())) {
            player.sendMessage(Component.text(
                "チャンネル名を指定した /server はProxy最高権限ユーザー専用です。",
                NamedTextColor.YELLOW));
            return;
        }
        String targetServer = settings.gameServers().stream()
            .filter(serverId -> serverId.equalsIgnoreCase(requestedTarget))
            .findFirst().orElse(null);
        RegisteredServer target = targetServer == null ? null : proxy.getServer(targetServer).orElse(null);
        if (target == null) {
            player.sendMessage(Component.text("接続先チャンネルが見つかりません。", NamedTextColor.RED));
            return;
        }
        ServerConnection current = player.getCurrentServer().orElse(null);
        if (current == null) {
            player.sendMessage(Component.text("現在の接続先を確認できません。", NamedTextColor.RED));
            return;
        }
        String currentServer = current.getServerInfo().getName();
        if (currentServer.equalsIgnoreCase(targetServer)) {
            player.sendMessage(Component.text("既にそのチャンネルへ接続しています。", NamedTextColor.YELLOW));
            return;
        }
        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0L) {
            player.sendMessage(Component.text(
                "再接続まで " + remaining + " 秒お待ちください。", NamedTextColor.YELLOW));
            return;
        }
        if (currentServer.equalsIgnoreCase(settings.lobbyServer())) {
            requestConnectionWithAdmission(player, currentServer, targetServer);
            return;
        }
        if (!settings.isGameServer(currentServer)) {
            player.sendMessage(Component.text("現在のサーバーからチャンネル移動できません。", NamedTextColor.RED));
            return;
        }
        AuthorityTransferPreparation preparation = new AuthorityTransferPreparation(
            currentServer,
            targetServer,
            System.currentTimeMillis() + AUTHORITY_TRANSFER_PREPARATION_TTL_MILLIS);
        AuthorityTransferPreparation selected = authorityTransferPreparations.compute(
            player.getUniqueId(),
            (ignored, existing) -> existing == null || existing.expiresAtMillis() < System.currentTimeMillis()
                ? preparation : existing);
        if (selected != preparation) {
            player.sendMessage(Component.text("サーバー移動を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        if (!current.sendPluginMessage(CHANNEL, BackendProtocol.prepareConnect(targetServer))) {
            authorityTransferPreparations.remove(player.getUniqueId(), preparation);
            player.sendMessage(Component.text("プレイヤーデータの保存を開始できませんでした。", NamedTextColor.RED));
        }
    }

    /** Proxy最高権限ユーザーへ/server引数として利用可能なRPG backend名を返す。 */
    static List<String> serverCommandSuggestions(
        NetworkSettings config,
        UUID playerId,
        String currentServer,
        String prefix
    ) {
        if (!config.isServerAuthority(playerId)) return List.of();
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return config.gameServers().stream()
            .filter(serverId -> currentServer == null || !serverId.equalsIgnoreCase(currentServer))
            .filter(serverId -> serverId.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
            .toList();
    }

    private void connectToGameServer(
        Player player,
        RegisteredServer target,
        String targetServer,
        boolean reserved
    ) {
        player.createConnectionRequest(target).connect().whenComplete((result, failure) -> {
            if (reserved) releaseServerSlot(targetServer);
            pendingGameConnections.remove(player.getUniqueId());
            if (failure == null && result != null && result.isSuccessful()) {
                lastGameConnectMillis.put(player.getUniqueId(), System.currentTimeMillis());
                return;
            }
            player.sendMessage(Component.text("サーバーへの接続に失敗しました。", NamedTextColor.RED));
        });
    }

    private boolean reserveServerSlot(String serverId, RegisteredServer server, int capacity) {
        AtomicInteger reservations = serverReservations.computeIfAbsent(
            serverId.toLowerCase(Locale.ROOT), ignored -> new AtomicInteger());
        while (true) {
            int current = reservations.get();
            if (server.getPlayersConnected().size() + current >= capacity) return false;
            if (reservations.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseServerSlot(String serverId) {
        AtomicInteger reservations = serverReservations.get(serverId.toLowerCase(Locale.ROOT));
        if (reservations != null) reservations.updateAndGet(value -> Math.max(0, value - 1));
    }

    private long cooldownRemaining(UUID playerId) {
        NetworkSettings settings = settings();
        if (settings == null) return 0L;
        Long last = lastGameConnectMillis.get(playerId);
        if (last == null) return 0L;
        long remaining = cooldownRemainingSeconds(
            last, System.currentTimeMillis(), settings.transferCooldownSeconds());
        if (remaining == 0L) lastGameConnectMillis.remove(playerId, last);
        return remaining;
    }

    /** LobbyまたはProxy最高権限ユーザーの接続元だけRPG接続要求を許可する。 */
    static boolean canRequestGameServerFrom(
        String sourceServer,
        NetworkSettings config,
        UUID playerId,
        boolean preparedAuthorityTransfer
    ) {
        return sourceServer != null && (sourceServer.equalsIgnoreCase(config.lobbyServer())
            || (config.isServerAuthority(playerId) && preparedAuthorityTransfer));
    }

    /** 準備済み最高権限転送以外はRPG接続中の直接接続を拒否する。 */
    static boolean shouldRejectCurrentGameConnection(boolean currentlyInGame, boolean preparedAuthorityTransfer) {
        return currentlyInGame && !preparedAuthorityTransfer;
    }

    private boolean consumeAuthorityTransferPreparation(UUID playerId, String sourceServer, String targetServer) {
        AuthorityTransferPreparation preparation = authorityTransferPreparations.remove(playerId);
        return matchesAuthorityTransferPreparation(
            preparation, sourceServer, targetServer, System.currentTimeMillis());
    }

    /** 一回限りの最高権限転送準備が接続元・接続先・有効期限と一致するか判定する。 */
    static boolean matchesAuthorityTransferPreparation(
        AuthorityTransferPreparation preparation,
        String sourceServer,
        String targetServer,
        long nowMillis
    ) {
        return preparation != null
            && preparation.expiresAtMillis() >= nowMillis
            && preparation.sourceServer().equalsIgnoreCase(sourceServer)
            && preparation.targetServer().equalsIgnoreCase(targetServer);
    }

    /** 最終RPG接続時刻から再接続クールタイムの残秒を算出する。 */
    static long cooldownRemainingSeconds(long lastConnectMillis, long nowMillis, long cooldownSeconds) {
        long expiresAt = lastConnectMillis + TimeUnit.SECONDS.toMillis(cooldownSeconds);
        return Math.max(0L, (expiresAt - nowMillis + 999L) / 1000L);
    }

    private void sendOpenMenu(Player player) {
        NetworkSettings settings = settings();
        if (settings == null) return;
        player.getCurrentServer().ifPresent(connection -> {
            if (!connection.getServerInfo().getName().equalsIgnoreCase(settings.lobbyServer())
                || !connection.sendPluginMessage(CHANNEL, BackendProtocol.openMenu())) {
                player.sendMessage(Component.text("サーバー選択画面を開けませんでした。", NamedTextColor.RED));
            }
        });
    }

    private void broadcastMinecraftChat(BackendProtocol.Chat chat) {
        Component message = Component.text("[" + chat.channel() + "] ", NamedTextColor.GRAY);
        if (!chat.className().isBlank()) {
            message = message.append(Component.text(
                "[Lv." + Math.max(1, chat.level()) + " " + chat.className() + "] ",
                NamedTextColor.AQUA));
        }
        message = message
            .append(Component.text(chat.displayName() + ": ", NamedTextColor.WHITE))
            .append(chatBodyComponent(chat.original(), chat.converted()));
        Component completedMessage = message;
        proxy.getAllPlayers().forEach(player -> player.sendMessage(completedMessage));
    }

    /** Proxy最高権限UUIDに一致するプレイヤーだけへプライベートチャット監視を配信する。 */
    void broadcastPrivateChat(String sourceServerId, BackendProtocol.PrivateChat chat) {
        NetworkSettings settings = settings();
        if (settings == null) return;
        Component message = Component.text("[監視] [" + settings.channelName(sourceServerId) + "] ", NamedTextColor.DARK_GRAY);
        if ("direct".equalsIgnoreCase(chat.type())) {
            message = message.append(Component.text(
                "[DM] " + chat.senderName() + " → " + chat.targetName() + ": ", NamedTextColor.LIGHT_PURPLE));
        } else if ("party".equalsIgnoreCase(chat.type())) {
            message = message.append(Component.text(
                "[パーティー: " + chat.partyName() + "] " + chat.senderName() + ": ", NamedTextColor.AQUA));
        } else {
            return;
        }
        Component completed = message.append(chatBodyComponent(chat.original(), chat.converted()));
        proxy.getAllPlayers().stream()
            .filter(player -> settings.isServerAuthority(player.getUniqueId()))
            .filter(player -> !isPrivateChatParticipant(player, chat))
            .forEach(player -> player.sendMessage(completed));
    }

    /**
     * 監視対象の管理者がプライベートチャット当事者かを判定します。
     * 当事者には通常配信が届くため、監視コピーを重ねて送信しません。
     *
     * @param player 判定対象の管理者
     * @param chat 監視対象チャット
     * @return チャット当事者ならtrue
     */
    boolean isPrivateChatParticipant(Player player, BackendProtocol.PrivateChat chat) {
        UUID playerId = player.getUniqueId();
        if (chat.participantIds().contains(playerId) || playerId.equals(chat.playerId())) {
            return true;
        }
        if (!"direct".equalsIgnoreCase(chat.type())) {
            return false;
        }
        if (player.getUsername().equalsIgnoreCase(chat.targetName())) {
            return true;
        }
        PlayerMetadata playerMetadata = metadata.get(playerId);
        return playerMetadata != null
            && playerMetadata.displayName().equalsIgnoreCase(chat.targetName());
    }

    /**
     * 異なるbackendにいるオンラインプレイヤーへDMを配送します。
     *
     * @param sourceServerId 送信元backend ID
     * @param sender 送信者
     * @param message 送信元backendから検証済みのDM
     */
    void deliverDirectMessage(
        String sourceServerId,
        Player sender,
        BackendProtocol.DirectMessage message
    ) {
        Player target = proxy.getAllPlayers().stream()
            .filter(player -> player.getUsername().equalsIgnoreCase(message.targetName()))
            .findFirst()
            .orElse(null);
        if (target == null) {
            sender.sendMessage(Component.text(
                "指定したプレイヤーが見つかりません: " + message.targetName(), NamedTextColor.RED));
            return;
        }
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(Component.text("自分自身にはDMを送信できません。", NamedTextColor.RED));
            return;
        }
        PlayerMetadata targetMetadata = metadata.get(target.getUniqueId());
        String targetName = targetMetadata == null ? target.getUsername() : targetMetadata.displayName();
        int targetLevel = targetMetadata == null || targetMetadata.level() == null
            ? 0 : targetMetadata.level();
        Component sent = directMessageComponent(
            "DM送信", message.senderLevel(), message.senderName(), targetLevel, targetName, message);
        Component received = directMessageComponent(
            "DM受信", message.senderLevel(), message.senderName(), targetLevel, targetName, message);
        sender.sendMessage(sent);
        target.sendMessage(received);
        broadcastPrivateChat(sourceServerId, new BackendProtocol.PrivateChat(
            sender.getUniqueId(), "direct", message.senderName(), targetName, "",
            message.original(), message.converted(), Set.of(sender.getUniqueId(), target.getUniqueId())));
    }

    /** Proxyから直接配送するDMの表示Componentを生成します。 */
    private static Component directMessageComponent(
        String direction,
        int senderLevel,
        String senderName,
        int targetLevel,
        String targetName,
        BackendProtocol.DirectMessage message
    ) {
        return Component.text("[", NamedTextColor.GRAY)
            .append(Component.text(direction, NamedTextColor.LIGHT_PURPLE))
            .append(Component.text("] [Lv." + directMessageLevel(senderLevel) + "] ", NamedTextColor.GRAY))
            .append(Component.text(senderName, NamedTextColor.WHITE))
            .append(Component.text(" -> [Lv." + directMessageLevel(targetLevel) + "] ", NamedTextColor.WHITE))
            .append(Component.text(targetName + ": ", NamedTextColor.WHITE))
            .append(chatBodyComponent(message.original(), message.converted()));
    }

    private static String directMessageLevel(int level) {
        return level > 0 ? String.valueOf(level) : "---";
    }

    static Component chatBodyComponent(String original, String converted) {
        Component source = Component.text(original, NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false);
        if (original.equals(converted)) {
            return source;
        }
        return source
            .append(Component.text("[", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            .append(Component.text(converted, NamedTextColor.GOLD, TextDecoration.ITALIC))
            .append(Component.text("]", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }

    private void pollDiscordChat() {
        if (!discordPollRunning.compareAndSet(false, true)) return;
        api.getDiscordChat(discordSequence.get()).whenComplete((batch, failure) -> {
            discordPollRunning.set(false);
            if (failure != null) {
                logger.warn("Failed to poll Discord chat", failure);
                return;
            }
            String generation = discordGenerationId.get();
            if (!batch.generationId().equals(generation)) {
                discordGenerationId.set(batch.generationId());
                discordSequence.set(0L);
                return;
            }
            batch.messages().forEach(message -> {
                discordSequence.set(message.sequence());
                Component component = Component.text("[Discord] ", NamedTextColor.BLUE)
                    .append(Component.text(message.authorName() + ": ", NamedTextColor.WHITE))
                    .append(Component.text(message.message(), NamedTextColor.WHITE));
                proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
            });
        });
    }

    private void refreshPresence() {
        NetworkSettings settings = settings();
        if (settings == null) return;
        long cooldownCutoff = System.currentTimeMillis()
            - TimeUnit.SECONDS.toMillis(settings.transferCooldownSeconds());
        lastGameConnectMillis.entrySet().removeIf(entry -> entry.getValue() <= cooldownCutoff
            && !pendingGameConnections.contains(entry.getKey()));
        long nowMillis = System.currentTimeMillis();
        authorityTransferPreparations.entrySet().removeIf(
            entry -> entry.getValue().expiresAtMillis() < nowMillis);
        for (Player player : proxy.getAllPlayers()) {
            String serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse(settings.lobbyServer());
            PlayerMetadata value = metadata.computeIfAbsent(
                player.getUniqueId(), ignored -> lobbyMetadata(player, serverId));
            if (!serverId.equalsIgnoreCase(value.serverId())) {
                value = value.withServer(serverId, settings.channelName(serverId));
                metadata.put(player.getUniqueId(), value);
            }
            api.heartbeatPlayer(value).exceptionally(failure -> null);
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            refreshServerPresence(server, settings);
        }
    }

    /**
     * Backendの到達性を非同期確認してNetwork APIへ状態を送信する。
     *
     * @param server 状態を確認するVelocity登録サーバー
     */
    private void refreshServerPresence(RegisteredServer server, NetworkSettings settings) {
        String serverId = server.getServerInfo().getName();
        ProxyConfig.ServerCapacity capacity = settings.capacity(serverId);
        server.ping().whenComplete((ignored, failure) -> {
            boolean online = failure == null;
            if (!online) serverMspt.remove(serverId.toLowerCase(Locale.ROOT));
            api.heartbeatServer(
                serverId,
                settings.channelName(serverId),
                online ? server.getPlayersConnected().size() : 0,
                online ? "online" : "offline",
                capacity
            ).exceptionally(apiFailure -> null);
        });
    }

    private synchronized void refreshTabEntries() {
        NetworkSettings settings = settings();
        if (settings == null) return;
        List<Player> connectedPlayers = List.copyOf(proxy.getAllPlayers());
        Set<UUID> onlineIds = new HashSet<>();
        connectedPlayers.forEach(player -> onlineIds.add(player.getUniqueId()));
        int totalPlayers = onlineIds.size();
        long nowNanos = System.nanoTime();
        serverMspt.entrySet().removeIf(entry -> resolveServerMspt(entry.getValue(), nowNanos) == null);
        for (Player viewer : connectedPlayers) {
            String currentServer = viewer.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().toLowerCase(Locale.ROOT))
                .orElse("");
            ProxyTabDisplay.HeaderFooter headerFooter = ProxyTabDisplay.render(
                config.tabServerAddress(), viewer.getPing(),
                resolveServerMspt(serverMspt.get(currentServer), nowNanos), totalPlayers);
            viewer.sendPlayerListHeaderAndFooter(headerFooter.header(), headerFooter.footer());
            TabList tabList = viewer.getTabList();
            removeStaleTabEntries(tabList, onlineIds);
            Map<UUID, PlayerMetadata> cached =
                tabDisplayCache.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
            cached.keySet().removeIf(playerId -> !onlineIds.contains(playerId));
            for (Player target : connectedPlayers) {
                PlayerMetadata value = metadata.getOrDefault(
                    target.getUniqueId(), lobbyMetadata(target, settings.lobbyServer()));
                Component displayName = tabDisplayName(value);
                var entry = tabList.getEntry(target.getUniqueId());
                if (entry.isEmpty()) {
                    tabList.addEntry(TabListEntry.builder()
                        .tabList(tabList)
                        .profile(target.getGameProfile())
                        .displayName(displayName)
                        .latency((int) Math.min(Integer.MAX_VALUE, target.getPing()))
                        .gameMode(0)
                        .build());
                } else {
                    TabListEntry currentEntry = entry.get();
                    Component currentDisplayName = currentEntry.getDisplayNameComponent().orElse(null);
                    if (!value.equals(cached.get(target.getUniqueId()))
                        || !displayName.equals(currentDisplayName)) {
                        currentEntry.setDisplayName(displayName);
                    }
                }
                cached.put(target.getUniqueId(), value);
            }
        }
    }

    /**
     * Proxyの現在オンライン一覧に存在しないTabエントリを削除する。
     *
     * @param tabList 同期対象のTab一覧
     * @param onlineIds Proxyに現在接続しているプレイヤーUUID
     */
    static void removeStaleTabEntries(TabList tabList, Set<UUID> onlineIds) {
        tabList.getEntries().stream()
            .map(entry -> entry.getProfile().getId())
            .filter(playerId -> !onlineIds.contains(playerId))
            .toList()
            .forEach(tabList::removeEntry);
    }

    /**
     * 退出したプレイヤーのTabエントリを全viewerから即時削除する。
     *
     * @param viewers Tabエントリを削除するviewer
     * @param playerId 退出したプレイヤーUUID
     */
    static void removeTabEntryFromAllViewers(Iterable<? extends Player> viewers, UUID playerId) {
        for (Player viewer : viewers) {
            viewer.getTabList().removeEntry(playerId);
        }
    }

    static boolean isCurrentBackend(String currentServer, String sourceServer) {
        return currentServer != null
            && sourceServer != null
            && currentServer.equalsIgnoreCase(sourceServer);
    }

    /** ProxyのTabエントリへ適用するRPG側準拠の表示名を生成する。 */
    static Component tabDisplayName(PlayerMetadata value) {
        if (value.accountNameOnly()) {
            return accountDisplayName(value);
        }
        Component prefix = Component.text("[" + value.channel() + "] ", NamedTextColor.GRAY);
        if (value.level() == null || value.className() == null) {
            return prefix.append(Component.text(value.mcid(), NamedTextColor.WHITE));
        }
        Component className = LEGACY_SERIALIZER.deserialize(value.className())
            .colorIfAbsent(NamedTextColor.AQUA);
        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(className);
        if (value.classLevelMax()) {
            classTag = classTag.append(Component.text(" MAX", NamedTextColor.RED, TextDecoration.BOLD));
        } else {
            classTag = classTag
                .append(Component.text(" Lv.", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(value.level()), NamedTextColor.YELLOW));
        }
        classTag = classTag.append(Component.text("] ", NamedTextColor.DARK_GRAY));
        Component afk = value.afk() ? Component.text("[AFK] ", NamedTextColor.RED) : Component.empty();
        return prefix
            .append(classTag)
            .append(afk)
            .append(accountDisplayName(value));
    }

    private static Component accountDisplayName(PlayerMetadata value) {
        String displayName = value.displayName();
        int slotSeparator = displayName.lastIndexOf('#');
        boolean hasSlotSuffix = slotSeparator >= 0
            && slotSeparator < displayName.length() - 1
            && displayName.substring(slotSeparator + 1).chars().allMatch(Character::isDigit);
        String accountName = hasSlotSuffix ? displayName.substring(0, slotSeparator) : displayName;
        Component name = Component.text(accountName,
            value.permission() == DONOR_PERMISSION ? NamedTextColor.AQUA : NamedTextColor.WHITE);
        if (value.permission() == DONOR_PERMISSION) {
            name = name.decorate(TextDecoration.BOLD);
        }
        return hasSlotSuffix
            ? name.append(Component.text(displayName.substring(slotSeparator), NamedTextColor.GRAY))
            : name;
    }

    private PlayerMetadata lobbyMetadata(Player player, String serverId) {
        NetworkSettings settings = settings();
        String channel = settings == null ? serverId : settings.channelName(serverId);
        return new PlayerMetadata(
            player.getUniqueId(), player.getUsername(), serverId, channel,
            player.getUsername(), null, null, false, 0, false, false);
    }

    private NetworkSettings settings() {
        return managedSettings.get();
    }

    static boolean isBanDeny(NetworkApiClient.Admission admission) {
        return "banned".equalsIgnoreCase(admission.denyReason())
            || admission.banIndefinite() || admission.banExpiresAtUtc() != null;
    }

    static Component admissionDenyComponent(NetworkApiClient.Admission admission) {
        if (isBanDeny(admission)) {
            return banDisconnectReason(
                admission.banIndefinite(), admission.banExpiresAtUtc(), admission.banReason(), OffsetDateTime.now());
        }
        return Component.text("このチャンネルは許可されたプレイヤーのみ参加できます。", NamedTextColor.RED);
    }

    /** BAN種別・期限・理由を含むプレイヤー向けの切断理由を生成する。 */
    static Component banDisconnectReason(
        boolean indefinite,
        OffsetDateTime expiresAtUtc,
        String reason,
        OffsetDateTime now
    ) {
        Component message = Component.text("このサーバーへの参加は禁止されています。", NamedTextColor.RED);
        if (indefinite || expiresAtUtc == null) {
            message = message.append(Component.newline())
                .append(Component.text("BAN期限: 無期限", NamedTextColor.YELLOW));
        } else {
            long remainingDays = Math.max(0L, ChronoUnit.DAYS.between(
                now.atZoneSameInstant(JAPAN_ZONE).toLocalDate(),
                expiresAtUtc.atZoneSameInstant(JAPAN_ZONE).toLocalDate()));
            message = message.append(Component.newline())
                .append(Component.text("BAN期限: あと" + remainingDays + "日", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("解除日時: " + BAN_EXPIRY_FORMAT.format(expiresAtUtc), NamedTextColor.YELLOW));
        }
        String displayedReason = reason == null || reason.isBlank() ? "運営にお問い合わせください。" : reason.trim();
        return message.append(Component.newline())
            .append(Component.text("理由: " + displayedReason, NamedTextColor.WHITE));
    }

    /**
     * 受信済みMSPTが有効期限内の場合だけ表示値として返す。
     *
     * @param metric 受信済みサーバーメトリクス
     * @param nowNanos 現在時刻
     * @return 有効なMSPT。未受信または期限切れの場合はnull
     */
    static Double resolveServerMspt(ServerMetric metric, long nowNanos) {
        if (metric == null) return null;
        long ageNanos = nowNanos - metric.receivedAtNanos();
        return ageNanos >= 0L && ageNanos <= SERVER_METRICS_TTL_NANOS ? metric.mspt() : null;
    }

    record ServerMetric(double mspt, long receivedAtNanos) {
    }

    record AuthorityTransferPreparation(
        String sourceServer,
        String targetServer,
        long expiresAtMillis
    ) {
    }

    record LifecycleNotification(String action, String message) {
    }

    private final class ServerMenuCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("プレイヤー専用コマンドです。"));
                return;
            }
            String[] arguments = invocation.arguments();
            if (arguments.length == 1 && !arguments[0].isBlank()) {
                requestAuthorityServerCommand(player, arguments[0].trim());
                return;
            }
            if (arguments.length > 0) {
                player.sendMessage(Component.text("使用方法: /server <チャンネル名>", NamedTextColor.YELLOW));
                return;
            }
            NetworkSettings settings = settings();
            if (settings == null) {
                player.sendMessage(Component.text("認証サーバーに接続できません。", NamedTextColor.RED));
                return;
            }
            String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse("");
            if (current.equalsIgnoreCase(settings.lobbyServer())) {
                sendOpenMenu(player);
                return;
            }
            player.sendMessage(Component.text("RPGサーバーから戻る場合は /lobby を使用してください。", NamedTextColor.YELLOW));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return List.of();
            NetworkSettings settings = settings();
            if (settings == null) return List.of();
            String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse(null);
            String[] arguments = invocation.arguments();
            String prefix = arguments.length == 1 ? arguments[0] : "";
            return arguments.length <= 1
                ? serverCommandSuggestions(settings, player.getUniqueId(), current, prefix)
                : List.of();
        }
    }
}
