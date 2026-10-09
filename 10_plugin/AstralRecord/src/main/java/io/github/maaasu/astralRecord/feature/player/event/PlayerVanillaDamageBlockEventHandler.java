package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーへのバニラダメージを抑止するイベントハンドラ。
 */
public class PlayerVanillaDamageBlockEventHandler extends AbstractEventHandler {

    private final WorldService worldService;
    private final BossChallengeService bossChallengeService;
    private final DungeonService dungeonService;

    /**
     * ワールドのマスターデータを参照するバニラダメージ抑止ハンドラを生成します。
     *
     * @param worldService WorldMasterData の解決とスポーン地点転送を担うサービス
     * @param bossChallengeService ボス挑戦の参加者死亡を処理するサービス
     * @param dungeonService ダンジョンの参加者死亡を処理するサービス
     */
    public PlayerVanillaDamageBlockEventHandler(
        @NotNull WorldService worldService,
        @NotNull BossChallengeService bossChallengeService,
        @NotNull DungeonService dungeonService
    ) {
        this.worldService = worldService;
        this.bossChallengeService = bossChallengeService;
        this.dungeonService = dungeonService;
    }

    /**
     * プレイヤーへの Bukkit ダメージイベントを常時キャンセルし、バニラの被ダメージモーションを抑止します。
     * 奈落ダメージはボス・ダンジョンの参加者死亡として処理し、対象外の場合は現在ワールドのマスタースポーンへ戻します。
     * 死亡回数・復帰待機・死亡上限は各挑戦サービスの既存規則を適用します。
     *
     * @param event ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        runSafely(() -> {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }

            if (isAllowedPlayerDamage(event, player)) {
                return;
            }

            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setDamage(0.0D);
                event.setCancelled(true);
                var astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    var deathLocation = player.getLocation();
                    if (bossChallengeService.handleParticipantVoidDeath(astPlayer, deathLocation)
                        || dungeonService.handleParticipantVoidDeath(astPlayer, deathLocation)) {
                        return;
                    }
                }
                teleportToWorldMasterSpawn(player);
                return;
            }

            event.setDamage(0.0D);
            event.setCancelled(true);
        }, LogId.E_3002, "vanilla_damage_block:" + event.getEntity().getName());
    }

    private boolean isAllowedPlayerDamage(
        @NotNull EntityDamageEvent event,
        @NotNull Player victim
    ) {
        if (!(event instanceof EntityDamageByEntityEvent byEntityEvent)) {
            return false;
        }
        Player attacker = resolvePlayerAttacker(byEntityEvent.getDamager());
        if (attacker == null) {
            return false;
        }
        var attackerModel = AstPlayerCache.get(attacker);
        var victimModel = AstPlayerCache.get(victim);
        return attackerModel != null
            && victimModel != null
            && attackerModel.isPvpEnabled()
            && victimModel.isPvpEnabled();
    }

    private Player resolvePlayerAttacker(@NotNull Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
            && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
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
