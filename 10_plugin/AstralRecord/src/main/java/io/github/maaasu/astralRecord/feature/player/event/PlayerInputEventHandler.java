package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInputEvent;

/**
 * プレイヤー入力状態を監視し、空中アクションに必要な jump 入力を処理するイベントハンドラです。
 */
public class PlayerInputEventHandler extends AbstractEventHandler {

    private final AirActionService airActionService;

    public PlayerInputEventHandler(AirActionService airActionService) {
        this.airActionService = airActionService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        runSafely(() -> {
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }

            airActionService.handleJumpInput(astPlayer, event.getInput().isJump());
        }, LogId.E_5171, event.getPlayer().getName());
    }
}
