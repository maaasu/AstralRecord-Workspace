package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * AstralRecord アイテムのバニラアクション（左/右クリック等）を抑止するイベントハンドラ。
 */
public class ItemInteractionBlockEventHandler extends AbstractEventHandler {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        runSafely(() -> {
            var action = event.getAction();
            if (action == Action.PHYSICAL) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }

            if (!isAstralItem(event.getItem())) {
                return;
            }

            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        runSafely(() -> {
            if (!isAstralItem(getItemInHand(event.getPlayer(), event.getHand()))) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        runSafely(() -> {
            if (!isAstralItem(getItemInHand(event.getPlayer(), event.getHand()))) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }
            if (!isAstralItem(player.getInventory().getItemInMainHand())) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getEntity().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        runSafely(() -> {
            if (!isAstralItem(event.getItem())) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    private static boolean isAstralItem(ItemStack item) {
        return item != null && item.getType() != org.bukkit.Material.AIR
                && ItemStackFactory.getAstralItemId(item) != null;
    }

    private static @NotNull ItemStack getItemInHand(@NotNull Player player, @NotNull EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private static boolean isPlayerMode(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }
}


