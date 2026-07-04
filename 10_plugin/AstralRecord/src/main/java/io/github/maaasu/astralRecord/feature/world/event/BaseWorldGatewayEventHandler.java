package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 拠点ワールドのゲートウェイ接触でオーバーワールド転送 GUI を開きます。
 */
public final class BaseWorldGatewayEventHandler extends AbstractEventHandler {
    private static final Set<Material> GATEWAY_BLOCKS = EnumSet.of(
            Material.END_GATEWAY,
            Material.END_PORTAL,
            Material.NETHER_PORTAL
    );
    private static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
    );

    private final Plugin plugin;
    private final OverworldTeleportService teleportService;
    private final OverworldTeleportGuiEventHandler guiEventHandler;
    private final Set<UUID> awaitingGatewayExit = new HashSet<>();

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
     * 拠点ワールドのゲートウェイブロックへ入った瞬間に GUI 起動処理を開始します。
     *
     * @param event プレイヤー移動イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        runSafely(() -> {
            Player player = event.getPlayer();
            UUID playerId = player.getUniqueId();
            boolean fromGateway = isGatewayBlock(event.getFrom());
            boolean toGateway = isGatewayBlock(event.getTo());
            if (!toGateway) {
                awaitingGatewayExit.remove(playerId);
                return;
            }
            if (fromGateway || awaitingGatewayExit.contains(playerId) || !teleportService.isBaseWorld(player.getWorld())) {
                return;
            }
            openGui(player);
        }, LogId.E_5754, event.getPlayer().getName(), "move");
    }

    /**
     * 拠点ワールドで発生したバニラのゲートウェイ系テレポートをキャンセルします。
     *
     * @param event プレイヤーテレポートイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        runSafely(() -> {
            if (!BLOCKED_CAUSES.contains(event.getCause()) || !teleportService.isBaseWorld(event.getFrom().getWorld())) {
                return;
            }
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (awaitingGatewayExit.contains(player.getUniqueId()) || guiEventHandler.isOpen(player)) {
                return;
            }
            openGui(player);
        }, LogId.E_5754, event.getPlayer().getName(), "teleport");
    }

    /**
     * 切断したプレイヤーのゲートウェイ再オープン抑止状態を破棄します。
     *
     * @param event プレイヤー切断イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> awaitingGatewayExit.remove(event.getPlayer().getUniqueId()), LogId.E_5754, event.getPlayer().getName(), "quit");
    }

    private void openGui(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        awaitingGatewayExit.add(playerId);

        Location spawnLocation = player.getWorld().getSpawnLocation();
        boolean teleported = player.teleport(spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!teleported && isGatewayBlock(player.getLocation())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                awaitingGatewayExit.remove(playerId);
                return;
            }
            guiEventHandler.open(player);
            if (!isGatewayBlock(player.getLocation())) {
                awaitingGatewayExit.remove(playerId);
            }
        });
    }

    private boolean isGatewayBlock(@Nullable Location location) {
        return location != null
                && location.getWorld() != null
                && GATEWAY_BLOCKS.contains(location.getBlock().getType());
    }
}
