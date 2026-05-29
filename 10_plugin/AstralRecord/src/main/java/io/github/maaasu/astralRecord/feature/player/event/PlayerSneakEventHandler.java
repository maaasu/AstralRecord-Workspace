package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * プレイヤーのしゃがみ操作を監視し、ドッジ発動条件を判定するイベントハンドラ。
 * <p>
 * しゃがみ開始時に開始時刻を記録し、しゃがみ解除時に
 * {@link DodgeService#tryTriggerOnSneakRelease(AstPlayer)} を呼び出します。
 */
public class PlayerSneakEventHandler extends AbstractEventHandler {

    private final AirActionService airActionService;
    private final DodgeService dodgeService;

    public PlayerSneakEventHandler(AirActionService airActionService, DodgeService dodgeService) {
        this.airActionService = airActionService;
        this.dodgeService = dodgeService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        runSafely(() -> {
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            if (!astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                return;
            }

            if (event.isSneaking()) {
                if (airActionService.tryStartWallCling(astPlayer)) {
                    return;
                }
                dodgeService.beginSneakWindow(astPlayer);
                return;
            }

            if (airActionService.releaseWallCling(astPlayer)) {
                return;
            }
            dodgeService.tryTriggerOnSneakRelease(astPlayer);
        }, LogId.E_5170, event.getPlayer().getName());
    }
}
