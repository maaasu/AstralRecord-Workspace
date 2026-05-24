package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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

    private final DodgeService dodgeService;

    public PlayerSneakEventHandler(DodgeService dodgeService) {
        this.dodgeService = dodgeService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        runSafely(() -> {
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }

            if (event.isSneaking()) {
                astPlayer.setSneakStartedAtMs(System.currentTimeMillis());
                astPlayer.setSneakStartedAtLocation(event.getPlayer().getLocation());
                return;
            }

            dodgeService.tryTriggerOnSneakRelease(astPlayer);
        }, LogId.E_5170, event.getPlayer().getName());
    }
}
