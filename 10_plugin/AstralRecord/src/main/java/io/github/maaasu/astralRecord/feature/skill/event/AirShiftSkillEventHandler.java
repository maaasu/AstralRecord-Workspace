package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.AirShiftSkillRuntimeService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** エアーシフトの着地待ち状態をプレイヤーのライフサイクルに合わせて解除します。 */
public final class AirShiftSkillEventHandler extends AbstractEventHandler {
    private final AirShiftSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取ってイベントハンドラを構築します。
     *
     * @param runtimeService エアーシフト状態サービス
     */
    public AirShiftSkillEventHandler(@NotNull AirShiftSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** プレイヤーの着地時に再発動ロックを解除します。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        runtimeService.clearActivationLockIfGrounded(event.getPlayer().getUniqueId(), to);
    }

    /** 死亡時に再発動ロックを解除します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runtimeService.clearActivationLock(event.getEntity().getUniqueId());
    }

    /** ログアウト時に再発動ロックを解除します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runtimeService.clearActivationLock(event.getPlayer().getUniqueId());
    }

    /** ワールド移動時に旧ワールドの再発動ロックを解除します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runtimeService.clearActivationLock(event.getPlayer().getUniqueId());
    }
}
