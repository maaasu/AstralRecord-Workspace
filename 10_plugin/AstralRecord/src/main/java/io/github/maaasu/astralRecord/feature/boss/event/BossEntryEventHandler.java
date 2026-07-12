package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Accepts a boss challenge when a player sneaks inside an entry circle.
 */
public final class BossEntryEventHandler extends AbstractEventHandler {
    private final BossChallengeService bossChallengeService;

    public BossEntryEventHandler(@NotNull BossChallengeService bossChallengeService) {
        this.bossChallengeService = bossChallengeService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        runSafely(
                () -> bossChallengeService.acceptNearestChallenge(event.getPlayer(), false),
                LogId.E_6501,
                event.getPlayer().getName()
        );
    }
}
