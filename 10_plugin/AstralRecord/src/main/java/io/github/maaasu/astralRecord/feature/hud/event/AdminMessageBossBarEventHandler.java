package io.github.maaasu.astralRecord.feature.hud.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.AdminMessageBossBarService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 管理者メッセージ BossBar の表示対象をプレイヤーの参加・退出に合わせて更新します。
 */
public final class AdminMessageBossBarEventHandler extends AbstractEventHandler {
    private final AdminMessageBossBarService bossBarService;

    /**
     * 管理者メッセージ BossBar のイベントハンドラを初期化します。
     *
     * @param bossBarService 管理者メッセージの表示サービス
     */
    public AdminMessageBossBarEventHandler(@NotNull AdminMessageBossBarService bossBarService) {
        this.bossBarService = bossBarService;
    }

    /**
     * 表示中の管理者メッセージを参加プレイヤーへ追加します。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        runSafely(
                () -> bossBarService.addPlayer(event.getPlayer()),
                LogId.E_3002,
                "AdminMessageBossBarEventHandler.onPlayerJoin"
        );
    }

    /**
     * 退出プレイヤーを表示中の管理者メッセージから削除します。
     *
     * @param event プレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
                () -> bossBarService.removePlayer(event.getPlayer()),
                LogId.E_3002,
                "AdminMessageBossBarEventHandler.onPlayerQuit"
        );
    }
}
