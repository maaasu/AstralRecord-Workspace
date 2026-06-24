package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps boss challenge participant state aligned with player sessions.
 */
public final class BossPlayerEventHandler extends AbstractEventHandler {
    private final BossChallengeService bossChallengeService;

    public BossPlayerEventHandler(@NotNull BossChallengeService bossChallengeService) {
        this.bossChallengeService = bossChallengeService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
                () -> bossChallengeService.handleQuit(event.getPlayer().getUniqueId()),
                LogId.E_6501,
                event.getPlayer().getName()
        );
    }
}
