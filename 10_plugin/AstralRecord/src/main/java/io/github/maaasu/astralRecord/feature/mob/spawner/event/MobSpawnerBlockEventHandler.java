package io.github.maaasu.astralRecord.feature.mob.spawner.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * スポナーアイテムの設置・破壊からスポナー座標を更新します。
 */
public class MobSpawnerBlockEventHandler extends AbstractEventHandler {

    private final MobSpawnerService spawnerService;

    /**
     * ハンドラを初期化します。
     *
     * @param spawnerService スポナーサービス
     */
    public MobSpawnerBlockEventHandler(@NotNull MobSpawnerService spawnerService) {
        this.spawnerService = spawnerService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        String spawnerId = spawnerService.readSpawnerId(event.getItemInHand());
        if (spawnerId == null) {
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }

        if (!spawnerService.registerLocation(spawnerId, event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5711.getId(), spawnerId));
            return;
        }

        event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5709.getId(), spawnerId));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!spawnerService.hasLocation(event.getBlock().getLocation())) {
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }

        if (spawnerService.removeLocation(event.getBlock().getLocation())) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5710.getId()));
        }
    }
}
