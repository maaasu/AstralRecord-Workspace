package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * プレイヤー設定のログイン時ロードとログアウト時クリアを行います。
 */
public final class PlayerSettingJoinEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final PlayerSettingService playerSettingService;

    public PlayerSettingJoinEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.playerSettingService = playerSettingService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID userId = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        long sessionToken = playerSettingService.beginSession(userId);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> playerSettingService.warmup(userId, sessionToken), LogId.E_5314, playerName)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> playerSettingService.clear(event.getPlayer().getUniqueId()), LogId.E_5314, event.getPlayer().getName());
    }
}
