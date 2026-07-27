package io.github.maaasu.astralRecord.feature.status.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ホットバーの選択アイテム変更に応じてプレイヤーステータスを再計算します。
 */
public class PlayerHeldItemStatusEventHandler extends AbstractEventHandler {

    private final StatusService statusService;
    private final AstralRecord plugin;

    /**
     * イベントハンドラを生成します。
     *
     * @param plugin プラグインインスタンス
     * @param statusService ステータス再計算サービス
     */
    public PlayerHeldItemStatusEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull StatusService statusService
    ) {
        this.plugin = plugin;
        this.statusService = statusService;
    }

    /**
     * ホットバー選択が変わった次 tick に、選択後のメインハンド装備を含めて再計算します。
     *
     * @param event Bukkit のホットバー選択変更イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        runSafely(() -> {
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (astPlayer.getBukkit().isOnline()) {
                    statusService.refreshStatus(astPlayer);
                }
            });
        }, LogId.E_3002, "status_held_item:" + event.getPlayer().getName());
    }
}
