package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
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

    private final WorldService worldService;

    /**
     * ワールドのマスターデータを参照するバニラダメージ抑止ハンドラを生成します。
     *
     * @param worldService WorldMasterData の解決とスポーン地点転送を担うサービス
     */
    public PlayerVanillaDamageBlockEventHandler(@NotNull WorldService worldService) {
        this.worldService = worldService;
    }

    /**
     * プレイヤーへの Bukkit ダメージイベントを常時キャンセルし、バニラの被ダメージモーションを抑止します。
     * 奈落ダメージの場合は、現在ワールドの WorldMasterData に定義されたスポーン地点へ戻します。
     *
     * @param event ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        runSafely(() -> {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }

            boolean wasCancelled = event.isCancelled();
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setDamage(0.0D);
                event.setCancelled(true);
                teleportToWorldMasterSpawn(player);
                return;
            }

            if (!wasCancelled
                    && event.getDamage() > 0.0D
                    && !SILENT_DAMAGE_CAUSES.contains(event.getCause())) {
                player.playHurtAnimation(0.0F);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.75F, 1.0F);
            }
            event.setDamage(0.0D);
            event.setCancelled(true);
        }, LogId.E_3002, "vanilla_damage_block:" + event.getEntity().getName());
    }

    private void teleportToWorldMasterSpawn(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData == null) {
            return;
        }
        if (!worldService.teleportToSpawnInWorld(player, worldData, player.getWorld())) {
            var spawn = worldData.spawnLocation();
            Logger.log(
                    LogId.W_5753,
                    worldData.id(),
                    player.getWorld().getName(),
                    spawn.x(),
                    spawn.y(),
                    spawn.z()
            );
        }
    }
}
