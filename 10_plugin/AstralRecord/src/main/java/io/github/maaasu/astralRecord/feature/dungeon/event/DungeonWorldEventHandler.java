package io.github.maaasu.astralRecord.feature.dungeon.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** ダンジョン内のブロック保護、部屋進入、ログアウトを処理します。 */
public final class DungeonWorldEventHandler extends AbstractEventHandler {
    private final DungeonService dungeonService;

    public DungeonWorldEventHandler(@NotNull DungeonService dungeonService) {
        this.dungeonService = dungeonService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!dungeonService.isDungeonWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
        PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_7016);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (!dungeonService.isDungeonWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
        PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_7016);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        if (dungeonService.isDungeonWorld(event.getLocation().getWorld())
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (dungeonService.isDungeonWorld(event.getEntity().getWorld())) {
            event.getDrops().clear();
        }
    }

    /** プレイヤーの部屋移動または向き変更を地図表示へ反映します。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            if (Float.compare(from.getYaw(), to.getYaw()) != 0) {
                runSafely(
                        () -> dungeonService.refreshOpenMap(event.getPlayer()),
                        LogId.E_7001,
                        event.getPlayer().getName(),
                        "map-look"
                );
            }
            return;
        }
        runSafely(
                () -> dungeonService.handleMove(event.getPlayer(), to),
                LogId.E_7001,
                event.getPlayer().getName(),
                "move"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
                () -> dungeonService.handleQuit(event.getPlayer().getUniqueId()),
                LogId.E_7001,
                event.getPlayer().getName(),
                "quit"
        );
    }
}
