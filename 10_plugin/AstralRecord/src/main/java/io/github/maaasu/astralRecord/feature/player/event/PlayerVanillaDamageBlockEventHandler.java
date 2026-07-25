package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * プレイヤーへのバニラダメージを抑止するイベントハンドラ。
 */
public class PlayerVanillaDamageBlockEventHandler extends AbstractEventHandler {

    private static final Set<EntityDamageEvent.DamageCause> SILENT_DAMAGE_CAUSES =
        EnumSet.of(
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.POISON
        );

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

            if (event.getDamage() > 0.0D && !SILENT_DAMAGE_CAUSES.contains(event.getCause())) {
                player.playHurtAnimation(0.0F);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.75F, 1.0F);
            }
            event.setDamage(0.0D);
            event.setCancelled(true);
        }, LogId.E_5600, event.getEntity().getName());
    }
}

