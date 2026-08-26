package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.JustDodgeSkillRuntimeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ジャスト回避の短命状態をプレイヤーのライフサイクルに合わせて破棄します。
 */
public final class JustDodgeSkillEventHandler extends AbstractEventHandler {
    private final JustDodgeSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取ってイベントハンドラを構築します。
     *
     * @param runtimeService ジャスト回避状態サービス
     */
    public JustDodgeSkillEventHandler(@NotNull JustDodgeSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** 死亡時にジャスト回避状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runtimeService.clearDodgeState(event.getEntity().getUniqueId());
    }

    /** ログアウト時にジャスト回避状態と設定を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runtimeService.clearPlayer(event.getPlayer().getUniqueId());
    }

    /** ワールド移動時に旧ワールドのジャスト回避状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runtimeService.clearDodgeState(event.getPlayer().getUniqueId());
    }
}
