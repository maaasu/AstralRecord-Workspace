package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.MeditationSkillRuntimeService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;

/**
 * メディテーションを中断するプレイヤーイベントを処理します。
 */
public final class MeditationSkillEventHandler extends AbstractEventHandler {
    private final MeditationSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取ってイベントハンドラを構築します。
     *
     * @param runtimeService メディテーション状態サービス
     */
    public MeditationSkillEventHandler(@NotNull MeditationSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * 正のダメージイベントを受けた時点でメディテーションを中断します。
     *
     * @param event ダメージイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDamage() <= 0.0D) {
            return;
        }
        runtimeService.interrupt(player.getUniqueId());
    }

    /**
     * 通常攻撃の事前入力でメディテーションを中断します。
     *
     * @param event 攻撃事前イベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPrePlayerAttackEntity(@NotNull PrePlayerAttackEntityEvent event) {
        runtimeService.interrupt(event.getPlayer().getUniqueId());
    }

    /**
     * スニーク解除時に効果と継続カウントを即時破棄します。
     *
     * @param event スニーク切替イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            runtimeService.interrupt(event.getPlayer().getUniqueId());
        }
    }

    /** 死亡時にメディテーション状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runtimeService.interrupt(event.getEntity().getUniqueId());
    }

    /** ログアウト時にメディテーション状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runtimeService.interrupt(event.getPlayer().getUniqueId());
    }

    /** ワールド移動時に旧ワールドの休息状態を破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        runtimeService.interrupt(event.getPlayer().getUniqueId());
    }
}
