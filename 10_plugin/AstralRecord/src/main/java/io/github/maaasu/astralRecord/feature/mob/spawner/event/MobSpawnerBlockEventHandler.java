package io.github.maaasu.astralRecord.feature.mob.spawner.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Optional;

/**
 * スポナーアイテムの設置・破壊からスポナー座標を更新します。
 */
public class MobSpawnerBlockEventHandler extends AbstractEventHandler {

    private static final double TARGET_DISTANCE = 6.0D;
    private static final double TARGET_RADIUS_SQ = 0.9D * 0.9D;

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
        event.setCancelled(true);

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }

        if (!spawnerService.registerLocation(spawnerId, event.getBlockPlaced().getLocation())) {
            event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5711.getId(), spawnerId));
            return;
        }

        consumePlacedItem(event.getPlayer(), event.getHand());
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Optional<Location> target = findTargetedSpawner(event.getPlayer());
        if (target.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }

        if (spawnerService.removeLocation(target.get())) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5710.getId()));
        }
    }

    private void consumePlacedItem(@NotNull Player player, @NotNull EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack itemStack = player.getInventory().getItem(hand);
        if (itemStack == null) {
            return;
        }
        if (itemStack.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
            return;
        }
        itemStack.setAmount(itemStack.getAmount() - 1);
        player.getInventory().setItem(hand, itemStack);
    }

    @NotNull
    private Optional<Location> findTargetedSpawner(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();

        return spawnerService.getLocations().stream()
                .map(location -> location.toLocation())
                .filter(location -> location != null && location.getWorld() == player.getWorld())
                .filter(location -> isTargeted(origin, direction, location.clone().add(0.0D, 0.75D, 0.0D)))
                .min(Comparator.comparingDouble(location -> location.distanceSquared(eye)));
    }

    private boolean isTargeted(@NotNull Vector origin, @NotNull Vector direction, @NotNull Location target) {
        Vector toTarget = target.toVector().subtract(origin);
        double projection = toTarget.dot(direction);
        if (projection < 0.0D || projection > TARGET_DISTANCE) {
            return false;
        }
        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return closest.distanceSquared(target.toVector()) <= TARGET_RADIUS_SQ;
    }
}
