package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 拠点ワールドのネザーポータル移動をキャンセルしてオーバーワールド転送 GUI を開きます。
 */
public final class BaseWorldGatewayEventHandler extends AbstractEventHandler {
    private final Plugin plugin;
    private final OverworldTeleportService teleportService;
    private final OverworldTeleportGuiEventHandler guiEventHandler;
    private final Set<UUID> pendingGuiOpen = new HashSet<>();

    public BaseWorldGatewayEventHandler(
            @NotNull Plugin plugin,
            @NotNull OverworldTeleportService teleportService,
            @NotNull OverworldTeleportGuiEventHandler guiEventHandler
    ) {
        this.plugin = plugin;
        this.teleportService = teleportService;
        this.guiEventHandler = guiEventHandler;
    }

    /**
     * 拠点ワールドのネザーポータル移動をキャンセルし、GUI 起動処理を開始します。
     *
     * @param event プレイヤーポータルイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerPortal(@NotNull PlayerPortalEvent event) {
        runSafely(() -> {
            if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    || !teleportService.isBaseWorld(event.getFrom().getWorld())) {
                return;
            }
            event.setCancelled(true);
            Player player = event.getPlayer();
            requestGuiOpen(player);
        }, LogId.E_5754, event.getPlayer().getName(), "portal");
    }

    /**
     * 切断したプレイヤーのゲートウェイ再オープン抑止状態を破棄します。
     *
     * @param event プレイヤー切断イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> {
            UUID playerId = event.getPlayer().getUniqueId();
            pendingGuiOpen.remove(playerId);
        }, LogId.E_5754, event.getPlayer().getName(), "quit");
    }

    private void requestGuiOpen(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (guiEventHandler.isOpen(player) || !pendingGuiOpen.add(playerId)) {
            return;
        }

        Location spawnLocation = player.getWorld().getSpawnLocation();
        Bukkit.getScheduler().runTask(plugin, () -> openGui(player, playerId));
        PlayerTeleportService.teleport(player, spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void openGui(@NotNull Player player, @NotNull UUID playerId) {
        if (!pendingGuiOpen.remove(playerId)) {
            return;
        }
        if (!player.isOnline() || !teleportService.isBaseWorld(player.getWorld())) {
            return;
        }
        if (guiEventHandler.isOpen(player)) {
            return;
        }
        guiEventHandler.open(player);
    }
}
