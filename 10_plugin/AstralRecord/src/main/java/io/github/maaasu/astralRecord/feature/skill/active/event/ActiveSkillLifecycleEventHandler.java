package io.github.maaasu.astralRecord.feature.skill.active.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillLifecycleService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーの lifecycle に合わせて発動中スキルと一時効果を破棄します。
 */
public final class ActiveSkillLifecycleEventHandler extends AbstractEventHandler {

    private final ActiveSkillLifecycleService lifecycleService;

    /** 破棄対象の runtime サービスで初期化します。 */
    public ActiveSkillLifecycleEventHandler(
            @NotNull ActiveSkillLifecycleService lifecycleService
    ) {
        this.lifecycleService = lifecycleService;
    }

    /** ログアウト時は cooldown を含むセッション状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
                () -> lifecycleService.clearAll(event.getPlayer().getUniqueId()),
                LogId.E_3002,
                "active_skill_quit:" + event.getPlayer().getName()
        );
    }

    /** 死亡時は継続攻撃と防御効果を残さず、再発動状態へ戻します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runSafely(
                () -> lifecycleService.clearAll(event.getEntity().getUniqueId()),
                LogId.E_3002,
                "active_skill_death:" + event.getEntity().getName()
        );
    }

    /** world 移動時は cooldown を保ったまま、旧 world の発動処理だけを破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runSafely(
                () -> lifecycleService.clearTransient(event.getPlayer().getUniqueId()),
                LogId.E_3002,
                "active_skill_world:" + event.getPlayer().getName()
        );
    }
}
