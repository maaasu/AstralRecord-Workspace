package io.github.maaasu.astralRecord.feature.player.afk.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * AFK判定へ参加・退出・移動・前後左右入力を渡すイベントハンドラです。
 */
public final class AfkPlayerEventHandler extends AbstractEventHandler {

    private final AfkService afkService;

    /**
     * AFKイベントハンドラを構築します。
     *
     * @param afkService AFK状態管理サービス
     */
    public AfkPlayerEventHandler(@NotNull AfkService afkService) {
        this.afkService = afkService;
    }

    /**
     * 参加プレイヤーのAFK判定を開始します。
     *
     * @param event 参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        runSafely(() -> afkService.onPlayerJoin(event.getPlayer()), LogId.E_3002, "afk_join:" + event.getPlayer().getName());
    }

    /**
     * 退出プレイヤーのAFK判定を破棄します。
     *
     * @param event 退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> afkService.onPlayerQuit(event.getPlayer()), LogId.E_3002, "afk_quit:" + event.getPlayer().getName());
    }

    /**
     * 前後左右入力の押下状態をAFK判定へ反映します。
     *
     * @param event 入力イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInput(@NotNull PlayerInputEvent event) {
        runSafely(() -> {
            var input = event.getInput();
            afkService.onDirectionalInput(
                event.getPlayer(),
                input.isForward() || input.isBackward() || input.isLeft() || input.isRight()
            );
        }, LogId.E_3002, "afk_input:" + event.getPlayer().getName());
    }

    /**
     * 移動後の位置をAFK判定へ反映します。
     *
     * @param event 移動または転送イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        runSafely(
            () -> afkService.onPlayerMove(event.getPlayer(), event.getTo(), event instanceof PlayerTeleportEvent),
            LogId.E_3002,
            "afk_move:" + event.getPlayer().getName()
        );
    }
}
