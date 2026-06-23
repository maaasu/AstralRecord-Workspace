package io.github.maaasu.astralRecord.feature.gathering.spawner.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class GatheringSpawnerBlockEventHandler extends AbstractEventHandler {
    private final GatheringSpawnerService spawnerService;

    public GatheringSpawnerBlockEventHandler(@NotNull GatheringSpawnerService spawnerService) {
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
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5719);
            return;
        }
        if (!spawnerService.registerLocation(spawnerId, event.getBlockPlaced().getLocation())) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5711, spawnerId);
            return;
        }

        consumePlacedItem(event.getPlayer(), event.getHand());
        PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5709, spawnerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!spawnerService.hasLocation(event.getBlock().getLocation())) {
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.setCancelled(true);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5719);
            return;
        }
        if (spawnerService.removeLocation(event.getBlock().getLocation())) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5710);
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
}
