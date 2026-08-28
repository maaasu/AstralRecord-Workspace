package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.SpellStepSkillRuntimeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** スペルステップの一時待機状態をプレイヤーのライフサイクルに合わせて破棄します。 */
public final class SpellStepSkillEventHandler extends AbstractEventHandler {
    private final SpellStepSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取ってイベントハンドラを構築します。
     *
     * @param runtimeService スペルステップ状態サービス
     */
    public SpellStepSkillEventHandler(@NotNull SpellStepSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** 死亡時にスペルステップの待機状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runtimeService.clearArmedState(event.getEntity().getUniqueId());
    }

    /** ログアウト時にスペルステップの設定と待機状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runtimeService.clearPlayer(event.getPlayer().getUniqueId());
    }

    /** ワールド移動時にスペルステップの待機状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runtimeService.clearArmedState(event.getPlayer().getUniqueId());
    }
}
