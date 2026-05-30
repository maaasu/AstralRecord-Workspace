package io.github.maaasu.astralRecord.feature.party.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ログアウト時のパーティー自動離脱を処理します。
 */
public final class PartyQuitEventHandler extends AbstractEventHandler {
    private final PartyService partyService;

    public PartyQuitEventHandler(@NotNull PartyService partyService) {
        this.partyService = partyService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
            () -> partyService.leaveOnLogout(event.getPlayer().getUniqueId(), event.getPlayer().getName()),
            LogId.E_6100,
            event.getPlayer().getName(),
            "party_quit"
        );
    }
}
