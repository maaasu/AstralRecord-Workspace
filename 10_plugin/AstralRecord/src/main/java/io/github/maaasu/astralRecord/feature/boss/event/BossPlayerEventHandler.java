package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.boss.service.BossMechanicService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps boss challenge participant state aligned with player sessions.
 */
public final class BossPlayerEventHandler extends AbstractEventHandler {
    private final BossChallengeService bossChallengeService;
    private final BossMechanicService bossMechanicService;

    /** @param bossChallengeService 挑戦の参加状態 @param bossMechanicService 転送後の通常波位置履歴を解除するサービス */
    public BossPlayerEventHandler(@NotNull BossChallengeService bossChallengeService, @NotNull BossMechanicService bossMechanicService) {
        this.bossChallengeService = bossChallengeService;
        this.bossMechanicService = bossMechanicService;
    }

    /** @param event 取消されていない転送。ゲート・復帰・移動魔法の架空の歩行経路を波から除外します */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        runSafely(() -> bossMechanicService.handlePlayerTeleport(event.getPlayer().getUniqueId()),
            LogId.E_6501, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
                () -> clearPlayerRuntime(event.getPlayer()),
                LogId.E_6501,
                event.getPlayer().getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    private void clearPlayerRuntime(@NotNull org.bukkit.entity.Player player) {
        bossChallengeService.handleQuit(player.getUniqueId());
    }
}
