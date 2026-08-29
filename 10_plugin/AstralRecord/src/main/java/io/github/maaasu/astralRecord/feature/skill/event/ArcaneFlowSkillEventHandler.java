package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.ArcaneFlowSkillRuntimeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** アーケインフローの直前スキル状態をプレイヤーのライフサイクルに合わせて破棄します。 */
public final class ArcaneFlowSkillEventHandler extends AbstractEventHandler {
    private final ArcaneFlowSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取ってイベントハンドラを構築します。
     *
     * @param runtimeService アーケインフロー状態サービス
     */
    public ArcaneFlowSkillEventHandler(@NotNull ArcaneFlowSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** 死亡時に直前スキル状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runtimeService.clearPreviousSkill(event.getEntity().getUniqueId());
    }

    /** ログアウト時に設定と直前スキル状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runtimeService.clearPlayer(event.getPlayer().getUniqueId());
    }

    /** ワールド移動時に旧ワールドの直前スキル状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runtimeService.clearPreviousSkill(event.getPlayer().getUniqueId());
    }
}
