package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.service.BaseMusicService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 拠点音楽の開始・停止をワールド移動とプレイヤーの退出へ同期します。
 */
public final class BaseMusicEventHandler extends AbstractEventHandler {
    private final BaseMusicService baseMusicService;

    /**
     * 拠点音楽イベントハンドラを初期化します。
     *
     * @param baseMusicService 拠点音楽サービス
     */
    public BaseMusicEventHandler(@NotNull BaseMusicService baseMusicService) {
        this.baseMusicService = baseMusicService;
    }

    /**
     * ワールド移動後の拠点音楽を現在設定へ同期します。
     *
     * @param event ワールド移動イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        baseMusicService.handleWorldChange(event.getPlayer());
    }

    /**
     * プレイヤー退出時に音楽と予約 task を停止候補へ戻します。
     *
     * @param event プレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        baseMusicService.handlePlayerQuit(event.getPlayer());
    }
}
