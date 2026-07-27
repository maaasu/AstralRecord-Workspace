package io.github.maaasu.astralRecord.feature.player.death;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * プレイヤー死亡状態に関わる Bukkit イベントを {@link PlayerDeathService} へ接続します。
 */
public final class PlayerDeathEventHandler extends AbstractEventHandler {

    private final PlayerDeathService deathService;

    /**
     * ハンドラを構築します。
     *
     * @param deathService プレイヤー死亡状態管理サービス
     */
    public PlayerDeathEventHandler(@NotNull PlayerDeathService deathService) {
        this.deathService = deathService;
    }

    /**
     * vanilla 死亡が発生した場合も AstralRecord の死亡状態へ寄せます。
     *
     * @param event プレイヤー死亡イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runSafely(() -> {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
            var astPlayer = AstPlayerCache.get(event.getEntity());
            if (astPlayer != null) {
                deathService.startDeath(astPlayer, event.getEntity().getLocation());
            }
        }, LogId.E_3002, "player_death:" + event.getEntity().getName());
    }

    /**
     * 再ログイン時に死亡状態の表示、非表示、固定位置を復元します。
     *
     * @param event 参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        runSafely(() -> deathService.handleJoin(event.getPlayer()), LogId.E_3002, "death_join:" + event.getPlayer().getName());
    }

    /**
     * 死亡中の切断時にタイトル表示を解除します。
     *
     * @param event 切断イベント
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> deathService.handleQuit(event.getPlayer()), LogId.E_3002, "death_quit:" + event.getPlayer().getName());
    }

    /**
     * 死亡中は死亡地点から移動できないようにします。
     *
     * @param event 移動イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        runSafely(() -> {
            UUID playerId = event.getPlayer().getUniqueId();
            Location lockLocation = deathService.lockLocation(playerId);
            Location to = event.getTo();
            if (lockLocation == null || to == null) {
                return;
            }
            if (event instanceof PlayerTeleportEvent) {
                return;
            }
            if (!hasPositionChanged(lockLocation, to)) {
                return;
            }

            Location corrected = lockLocation.clone();
            corrected.setYaw(to.getYaw());
            corrected.setPitch(to.getPitch());
            event.setTo(corrected);
        }, LogId.E_3002, "death_move_lock:" + event.getPlayer().getName());
    }

    private boolean hasPositionChanged(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() != to.getWorld()) {
            return true;
        }
        return from.getX() != to.getX()
            || from.getY() != to.getY()
            || from.getZ() != to.getZ();
    }
}
