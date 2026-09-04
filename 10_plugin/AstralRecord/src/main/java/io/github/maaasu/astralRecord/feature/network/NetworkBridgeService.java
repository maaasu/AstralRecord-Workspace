package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
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
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** RPGサーバーとVelocity Proxy間の転送・チャット・Tabメタデータを管理します。 */
public final class NetworkBridgeService implements NetworkChatBridge, Listener {
    private final AstralRecord plugin;
    private final PlayerClassService playerClassService;
    private final AfkService afkService;
    private final boolean enabled;
    private final String channelName;
    private final String lobbyServer;
    private final Set<UUID> transfers = ConcurrentHashMap.newKeySet();
    private BukkitTask metadataTask;

    public NetworkBridgeService(
        @NotNull AstralRecord plugin,
        @NotNull PlayerClassService playerClassService,
        @NotNull AfkService afkService
    ) {
        this.plugin = plugin;
        this.playerClassService = playerClassService;
        this.afkService = afkService;
        this.enabled = plugin.getConfig().getBoolean("network.enabled", true);
        this.channelName = plugin.getConfig().getString("network.channelName", "dev");
        this.lobbyServer = plugin.getConfig().getString("network.lobbyServer", "lobby");
    }

    public void start() {
        playerClassService.setPlayerListNameUpdatesEnabled(!enabled);
        if (!enabled) return;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BackendProtocol.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        metadataTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::publishNetworkState,
            20L,
            100L);
    }

    public void stop() {
        if (metadataTask != null) metadataTask.cancel();
        metadataTask = null;
        if (enabled) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BackendProtocol.CHANNEL);
            HandlerList.unregisterAll(this);
        }
        transfers.clear();
        playerClassService.setPlayerListNameUpdatesEnabled(true);
    }

    public void onPlayerLoaded(@NotNull AstPlayer player) {
        if (enabled) publishMetadata(player);
    }

    @Override
    public boolean publish(@NotNull Player sender, @NotNull String message) {
        if (!enabled || !sender.isOnline()) return false;
        AstPlayer player = AstPlayerCache.get(sender);
        if (player == null) return false;
        BackendProtocol.sendChat(
            plugin, player, channelName, displayName(player), plainClassName(player), message);
        return true;
    }

    /** 全保存の成功後にだけProxyへロビー接続を要求します。 */
    public void transferToLobby(@NotNull AstPlayer player) {
        Player bukkit = player.getBukkit();
        UUID playerId = bukkit.getUniqueId();
        if (!enabled) {
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7150);
            return;
        }
        if (!transfers.add(playerId)) {
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7151);
            return;
        }
        var skillBindGuiEventHandler = plugin.getSkillBindGuiEventHandler();
        if (skillBindGuiEventHandler != null) {
            skillBindGuiEventHandler.releaseForAccountSwitch(bukkit);
        }
        var tradeService = plugin.getTradeService();
        if (tradeService != null) {
            tradeService.cancelTrade(bukkit);
        }
        bukkit.closeInventory();
        bukkit.setInvulnerable(true);
        PlayerJoinEventHandler.AccountSwitchPreparation preparation =
            plugin.getPlayerJoinEventHandler().prepareAccountSwitch(bukkit);
        if (preparation == null) {
            transfers.remove(playerId);
            bukkit.setInvulnerable(false);
            PlayerMessageService.getInstance().send(bukkit, PlayerMsgId.P_7152);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeException failure = null;
            try {
                plugin.getPlayerService().awaitQueuedSavesForAccountSwitch(
                    preparation.accountId(),
                    preparation.logoutSave()
                );
            } catch (RuntimeException exception) {
                failure = exception;
            }
            RuntimeException saveFailure = failure;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!bukkit.isOnline()) {
                    transfers.remove(playerId);
                    return;
                }
                if (saveFailure != null) {
                    transfers.remove(playerId);
                    bukkit.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_7153.getId()));
                    return;
                }
                BackendProtocol.sendConnect(plugin, bukkit, lobbyServer);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    boolean stillPending = transfers.remove(playerId);
                    if (bukkit.isOnline() && stillPending) {
                        bukkit.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_7154.getId()));
                    }
                }, 100L);
            });
        });
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
        transfers.remove(event.getPlayer().getUniqueId());
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
