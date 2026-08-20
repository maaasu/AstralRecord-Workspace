package io.github.maaasu.astralRecord.feature.combat.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Bukkit を経由する非 AstralRecord ダメージを抑止するイベントハンドラ。
 */
public final class BukkitDamageBlockEventHandler extends AbstractEventHandler {

    /**
     * Bukkit が発火したダメージを、対象・原因・事前キャンセル状態にかかわらず無効化します。
     * AstralRecord のダメージは {@code DamageService} が直接反映するため、この抑止の対象外です。
     *
     * @param event Bukkit ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        runSafely(
                () -> {
                    event.setDamage(0.0D);
                    event.setCancelled(true);
                },
                LogId.E_5900,
                event.getEntity().getName()
        );
    }
}
