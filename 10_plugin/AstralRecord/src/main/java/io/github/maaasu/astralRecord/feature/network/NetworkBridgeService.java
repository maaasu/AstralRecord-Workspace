package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.ChatMessageConversion;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.InvulnerabilityVisualService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** RPGサーバーとVelocity Proxy間の転送・チャット・Tabメタデータを管理します。 */
public final class NetworkBridgeService implements NetworkChatBridge, Listener, PluginMessageListener {
    private final AstralRecord plugin;
    private final PlayerClassService playerClassService;
    private final AfkService afkService;
    private final QuestService questService;
    private final PlayerSessionTransitionGuard transitionGuard;
    private final boolean enabled;
    private final String channelName;
    private final String lobbyServer;
    private final Set<UUID> transfers = ConcurrentHashMap.newKeySet();
    private final NetworkAuthorityClient authorityClient = new NetworkAuthorityClient();
    private final NetworkChannelAccessService channelAccessService = NetworkChannelAccessService.getInstance();
    private final AtomicBoolean authorityRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean authorityWarningLogged = new AtomicBoolean();
    private @Nullable InvulnerabilityVisualService invulnerabilityVisualService;
    private BukkitTask metadataTask;
    private BukkitTask authorityTask;

    public NetworkBridgeService(
        @NotNull AstralRecord plugin,
        @NotNull PlayerClassService playerClassService,
        @NotNull AfkService afkService,
        @NotNull QuestService questService,
        @NotNull PlayerSessionTransitionGuard transitionGuard
    ) {
        this.plugin = plugin;
        this.playerClassService = playerClassService;
        this.afkService = afkService;
        this.questService = questService;
        this.transitionGuard = transitionGuard;
        this.enabled = plugin.getConfig().getBoolean("network.enabled", true);
        this.channelName = plugin.getConfig().getString("network.channelName", "dev");
        this.lobbyServer = plugin.getConfig().getString("network.lobbyServer", "lobby");
    }

    /**
     * チャンネル転送中のプレイヤー無敵を黄色発光へ同期するサービスを設定します。
     *
     * @param service 無敵表示サービス
     */
    public void setInvulnerabilityVisualService(@NotNull InvulnerabilityVisualService service) {
        this.invulnerabilityVisualService = service;
    }

    public void start() {
        playerClassService.setPlayerListNameUpdatesEnabled(!enabled);
        if (!enabled) return;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BackendProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
            plugin, BackendProtocol.CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        metadataTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::publishNetworkState,
            20L,
            100L);
        long authorityRefreshTicks = Math.max(20L,
            plugin.getConfig().getLong("network.authorityRefreshTicks", 100L));
        authorityTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::scheduleAuthorityRefresh, 0L, authorityRefreshTicks);
    }

    public void stop() {
        if (metadataTask != null) metadataTask.cancel();
        metadataTask = null;
        if (authorityTask != null) authorityTask.cancel();
        authorityTask = null;
        if (enabled) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BackendProtocol.CHANNEL);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, BackendProtocol.CHANNEL, this);
            HandlerList.unregisterAll(this);
        }
        transfers.forEach(playerId ->
            transitionGuard.end(playerId, PlayerSessionTransitionGuard.Transition.CHANNEL_TRANSFER));
        transfers.clear();
        NetworkAuthorityRegistry.clear();
        playerClassService.setPlayerListNameUpdatesEnabled(true);
    }

    public void onPlayerLoaded(@NotNull AstPlayer player) {
        if (enabled) publishMetadata(player);
    }

    @Override
    public boolean publish(@NotNull Player sender, @NotNull ChatMessageConversion message) {
        if (!enabled || !sender.isOnline()) return false;
        AstPlayer player = AstPlayerCache.get(sender);
        if (player != null) {
            BackendProtocol.sendChat(
                plugin, player, channelName, displayName(player), plainClassName(player), message);
        } else {
            // プレイヤーデータのロード完了を待つと、この発言だけが同一サーバーに閉じてしまう。
            BackendProtocol.sendChat(plugin, sender, channelName, sender.getName(), 0, "", message);
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void publishDirectMessage(
        @NotNull Player sender,
        @NotNull String senderName,
        @NotNull String targetName,
        @NotNull ChatMessageConversion message
    ) {
        if (!enabled || !sender.isOnline()) return;
        BackendProtocol.sendPrivateChat(
            plugin, sender, "direct", senderName, targetName, "", message);
    }

    /** {@inheritDoc} */
    @Override
    public boolean publishRemoteDirectMessage(
        @NotNull Player sender,
        @NotNull String targetName,
        @NotNull ChatMessageConversion message
    ) {
        if (!enabled || !sender.isOnline()) return false;
        AstPlayer player = AstPlayerCache.get(sender);
        String displayName = player == null ? sender.getName() : displayName(player);
        int level = player == null ? 0 : player.getClassLevel();
        BackendProtocol.sendDirectMessage(
            plugin, sender, targetName, displayName, level, message);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void publishPartyMessage(
        @NotNull Player sender,
        @NotNull String senderName,
        @NotNull String partyName,
        @NotNull ChatMessageConversion message
    ) {
        if (!enabled || !sender.isOnline()) return;
        BackendProtocol.sendPrivateChat(
            plugin, sender, "party", senderName, "", partyName, message);
    }

    /** 全保存の成功後にだけProxyへロビー接続を要求します。 */
    public void transferToLobby(@NotNull AstPlayer player) {
        transferToServer(player, lobbyServer);
    }

    /**
     * 現在のプレイヤーデータを保存し、ACK成功後にだけProxyへ接続を要求します。
     *
     * @param player 移動対象プレイヤー
     * @param targetServer Proxy設定に登録された接続先backend名
     */
    void transferToServer(@NotNull AstPlayer player, @NotNull String targetServer) {
        Player bukkit = player.getBukkit();
        UUID playerId = bukkit.getUniqueId();
        if (!enabled) {
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7150);
            return;
        }
        if (!transitionGuard.tryBegin(
            playerId,
            PlayerSessionTransitionGuard.Transition.CHANNEL_TRANSFER
        )) {
            PlayerMsgId messageId = transitionGuard.current(playerId)
                == PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH
                ? PlayerMsgId.P_5341
                : PlayerMsgId.P_7151;
            PlayerMessageService.getInstance().send(bukkit, messageId);
            return;
        }
        if (!transfers.add(playerId)) {
            transitionGuard.end(playerId, PlayerSessionTransitionGuard.Transition.CHANNEL_TRANSFER);
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7151);
            return;
        }
        var skillBindGuiEventHandler = plugin.getSkillBindGuiEventHandler();
        if (skillBindGuiEventHandler != null) {
            skillBindGuiEventHandler.releaseForAccountSwitch(bukkit);
        }
        var tradeService = plugin.getTradeService();
        if (tradeService != null) {
            tradeService.cancelRelatedSessions(bukkit);
        }
        bukkit.closeInventory();
        setPlayerInvulnerable(bukkit, true);
        CompletableFuture<Boolean> playerStateSave;
        CompletableFuture<Void> questStateSave;
        try {
            playerStateSave = plugin.getPlayerService().saveForChannelTransfer(player);
            questStateSave = questService.flushState(player.getAccount().getUuid());
        } catch (RuntimeException failure) {
            releaseTransfer(playerId);
            setPlayerInvulnerable(bukkit, false);
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7153);
            return;
        }

        CompletableFuture.allOf(playerStateSave, questStateSave).whenComplete((ignored, failure) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!bukkit.isOnline()) {
                    releaseTransfer(playerId);
                    return;
                }
                if (failure != null || !Boolean.TRUE.equals(playerStateSave.getNow(false))) {
                    releaseTransfer(playerId);
                    setPlayerInvulnerable(bukkit, false);
                    PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7153);
                    return;
                }
                try {
                    BackendProtocol.sendConnect(plugin, bukkit, targetServer);
                } catch (RuntimeException connectFailure) {
                    releaseTransfer(playerId);
                    setPlayerInvulnerable(bukkit, false);
                    PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7154);
                    return;
                }
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    boolean stillPending = transfers.contains(playerId);
                    releaseTransfer(playerId);
                    if (bukkit.isOnline() && stillPending) {
                        setPlayerInvulnerable(bukkit, false);
                        PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7154);
                    }
                }, 100L);
            })
        );
    }

    /**
     * Proxyからの保存付き接続準備要求を受け取ります。
     *
     * @param channel 受信チャンネル
     * @param player 対象プレイヤー
     * @param message Plugin message payload
     */
    @Override
    public void onPluginMessageReceived(
        @NotNull String channel,
        @NotNull Player player,
        byte @NotNull [] message
    ) {
        if (!enabled || !BackendProtocol.CHANNEL.equals(channel)) return;
        String targetServer;
        try {
            targetServer = BackendProtocol.decodePrepareConnect(message);
        } catch (java.io.IOException ignored) {
            return;
        }
        if (!NetworkAuthorityRegistry.isAuthority(player.getUniqueId())
            && !NetworkChannelAccessRegistry.isAuthority(player.getUniqueId())) return;
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7152);
            return;
        }
        transferToServer(astPlayer, targetServer);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        if (!transfers.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX()
            || event.getFrom().getY() != event.getTo().getY()
            || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && transfers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && transfers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        if (transfers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPickupItem(@NotNull EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && transfers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        if (transfers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (transfers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (transfers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        if (transfers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        releaseTransfer(event.getPlayer().getUniqueId());
    }

    private void releaseTransfer(@NotNull UUID playerId) {
        transfers.remove(playerId);
        transitionGuard.end(playerId, PlayerSessionTransitionGuard.Transition.CHANNEL_TRANSFER);
    }

    private void setPlayerInvulnerable(@NotNull Player player, boolean value) {
        if (invulnerabilityVisualService == null) {
            player.setInvulnerable(value);
        } else {
            invulnerabilityVisualService.setInvulnerable(player, value);
        }
    }

    private void publishMetadata(@NotNull AstPlayer player) {
        if (!player.getBukkit().isOnline()) return;
        BackendProtocol.sendMetadata(
            plugin, player, channelName, displayName(player), tabClassName(
                playerClassService.getShortDisplayName(player.getClassId()), player.getClassId()),
            afkService.isAfk(player));
    }

    /** プレイヤーTabメタデータとサーバー平均MSPTをProxyへ送る。 */
    private void publishNetworkState() {
        Player metricsSender = null;
        for (AstPlayer player : AstPlayerCache.getAll()) {
            publishMetadata(player);
            if (metricsSender == null && player.getBukkit().isOnline()) metricsSender = player.getBukkit();
        }
        if (metricsSender != null) {
            BackendProtocol.sendServerMetrics(plugin, metricsSender, Bukkit.getServer().getAverageTickTime());
        }
    }

    /** メインスレッドで更新対象UUIDを取得し、API照会だけを非同期へ渡す。重複起動は抑止する。 */
    private void scheduleAuthorityRefresh() {
        if (!authorityRefreshRunning.compareAndSet(false, true)) return;
        try {
            Set<UUID> onlinePlayerIds = AstPlayerCache.getAll().stream()
                .map(player -> player.getBukkit().getUniqueId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            plugin.getServer().getScheduler().runTaskAsynchronously(
                plugin, () -> refreshAuthorityUsers(onlinePlayerIds));
        } catch (RuntimeException exception) {
            authorityRefreshRunning.set(false);
            throw exception;
        }
    }

    /**
     * Network APIの最高権限とチャンネルロールを非同期取得する。
     * @param onlinePlayerIds メインスレッドで確定した更新対象UUID。Bukkit Playerを保持しない
     */
    private void refreshAuthorityUsers(Set<UUID> onlinePlayerIds) {
        try {
            Set<UUID> authorities = authorityClient.getAuthorities();
            NetworkAuthorityRegistry.replace(authorities);
            authorityWarningLogged.set(false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logAuthorityWarningOnce(exception);
        } catch (RuntimeException | java.io.IOException exception) {
            logAuthorityWarningOnce(exception);
        } finally {
            onlinePlayerIds.forEach(channelAccessService::refresh);
            authorityRefreshRunning.set(false);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                AstPlayerCache.getAll().forEach(AstPlayer::refreshEffectivePermission));
        }
    }

    /** 最高権限一覧の連続取得失敗を一度だけログへ記録する。 */
    private void logAuthorityWarningOnce(Throwable failure) {
        if (authorityWarningLogged.compareAndSet(false, true)) {
            Logger.log(LogId.W_7120, failure, failure.getClass().getSimpleName());
        }
    }

    private @NotNull String displayName(@NotNull AstPlayer player) {
        return AccountDisplayNameFormatter.toPlain(player.getAccount());
    }

    private @NotNull String plainClassName(@NotNull AstPlayer player) {
        String raw = playerClassService.getShortDisplayName(player.getClassId());
        String stripped = ColorCodeUtil.stripColor(raw);
        return stripped == null || stripped.isBlank() ? player.getClassId() : stripped;
    }

    static @NotNull String tabClassName(String raw, @NotNull String fallback) {
        return ColorCodeUtil.toLegacyText(raw, fallback);
    }
}
