package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーへのバニラダメージを抑止するイベントハンドラ。
 */
public class PlayerVanillaDamageBlockEventHandler extends AbstractEventHandler {

    /**
     * プレイヤーへの EntityDamageEvent を常時キャンセルし、バニラの被ダメージモーションを抑止する。
     *
     * @param event ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        runSafely(() -> {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }
            if (AstPlayerCache.get(player) == null) {
                return;
            }

            event.setDamage(0.0D);
            event.setCancelled(true);
        }, LogId.E_5600, event.getEntity().getName());
    }
}

