package io.github.maaasu.astralRecord.feature.teleporter.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー参加、退出、ワールド移動時のウェイストーン表示を同期します。
 */
public final class TeleporterPlayerEventHandler extends AbstractEventHandler {
    private static final int JOIN_SYNC_MAX_ATTEMPTS = 12;
    private static final long JOIN_SYNC_RETRY_DELAY_TICKS = 20L;

    private final Plugin plugin;
    private final TeleporterService teleporterService;

    public TeleporterPlayerEventHandler(@NotNull Plugin plugin, @NotNull TeleporterService teleporterService) {
        this.plugin = plugin;
        this.teleporterService = teleporterService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        runSafely(() -> scheduleJoinSync(event.getPlayer(), 0), LogId.E_5955, event.getPlayer().getName(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runSafely(() -> teleporterService.syncView(event.getPlayer()), LogId.E_5955, event.getPlayer().getName(), "world");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> teleporterService.clearPlayer(event.getPlayer()), LogId.E_5955, event.getPlayer().getName(), "quit");
    }

    private void scheduleJoinSync(@NotNull Player player, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                runSafely(() -> syncJoinedPlayer(player, attempt), LogId.E_5955, player.getName(), "join"), JOIN_SYNC_RETRY_DELAY_TICKS);
    }

    private void syncJoinedPlayer(@NotNull Player player, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            if (attempt + 1 < JOIN_SYNC_MAX_ATTEMPTS) {
                scheduleJoinSync(player, attempt + 1);
            }
            return;
        }
        teleporterService.loadUnlockStateAsync(astPlayer).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                Bukkit.getScheduler().runTask(plugin, () -> teleporterService.syncView(player));
            }
        });
    }
}
