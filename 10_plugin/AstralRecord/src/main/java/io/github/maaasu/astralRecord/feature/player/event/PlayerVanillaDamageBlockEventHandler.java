package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーへのバニラダメージを抑止するイベントハンドラ。
 */
public class PlayerVanillaDamageBlockEventHandler extends AbstractEventHandler {

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

            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setDamage(0.0D);
                event.setCancelled(true);
                teleportToWorldMasterSpawn(player);
                return;
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
