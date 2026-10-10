package io.github.maaasu.astralRecord.feature.guide.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.guide.service.GuideActionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/** プレイヤーのセッション・ワールド遷移時にガイドの一時表示を回収します。 */
public final class GuideHighlightEventHandler extends AbstractEventHandler {
    private final GuideActionService guideActionService;

    /**
     * ガイド表示の回収先を設定します。
     *
     * @param guideActionService メインスレッドで案内表示を管理するサービス
     */
    public GuideHighlightEventHandler(@NotNull GuideActionService guideActionService) {
        this.guideActionService = guideActionService;
    }

    /**
     * 切断したプレイヤーの表示と満了タスクを回収します。
     *
     * @param event 切断イベント
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        guideActionService.clearHighlight(event.getPlayer().getUniqueId());
    }

    /**
     * テレポートが成立した場合に旧位置の案内表示を回収します。
     *
     * @param event テレポートイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        guideActionService.clearHighlight(event.getPlayer().getUniqueId());
    }

    /**
     * ワールド変更後にも旧ワールドの案内状態を回収します。
     *
     * @param event ワールド変更イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        guideActionService.clearHighlight(event.getPlayer().getUniqueId());
    }

    /**
     * 死亡時に案内表示を回収し、リスポーン後へ持ち越さないようにします。
     *
     * @param event プレイヤー死亡イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        guideActionService.clearHighlight(event.getEntity().getUniqueId());
    }
}
