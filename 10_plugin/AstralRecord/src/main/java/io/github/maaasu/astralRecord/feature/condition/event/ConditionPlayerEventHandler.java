package io.github.maaasu.astralRecord.feature.condition.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーの状態異常 lifecycle と移動制限を Bukkit イベントへ接続します。
 */
public final class ConditionPlayerEventHandler extends AbstractEventHandler {
    private final ConditionService conditionService;

    public ConditionPlayerEventHandler(@NotNull ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            if (conditionService.canMove(AstEntity.player(astPlayer))) {
                return;
            }
            if (event.getFrom().getWorld() == event.getTo().getWorld()
                    && event.getFrom().distanceSquared(event.getTo()) > 1.0E-6D) {
                event.setTo(event.getFrom());
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, player.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, player.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer != null && !conditionService.canInteract(AstEntity.player(astPlayer))) {
                event.setCancelled(true);
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer != null) {
                conditionService.clearAll(AstEntity.player(astPlayer));
            }
        }, LogId.E_5900, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getEntity());
            if (astPlayer != null) {
                conditionService.clearAll(AstEntity.player(astPlayer));
            }
        }, LogId.E_5900, event.getEntity().getName());
    }
}
