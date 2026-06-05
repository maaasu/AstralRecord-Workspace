package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * スキルツリー管理アイテムとプレイヤー操作のイベントを扱います。
 */
public class SkillTreeEventHandler extends AbstractEventHandler {
    private final SkillTreeService service;

    public SkillTreeEventHandler(@NotNull SkillTreeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAdminInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack itemStack = event.getItem();
        String positionId = service.readPositionItemId(itemStack);
        boolean connector = service.isConnectorItem(itemStack);
        if (positionId == null && !connector) {
            return;
        }
        event.setCancelled(true);

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!service.isAdminMode(astPlayer)) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        if (positionId != null) {
            handlePositionItem(event, positionId);
            return;
        }
        handleConnectorItem(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!service.isPlayerModeSkillTree(event.getPlayer())) {
            return;
        }
        if (!service.shouldUseSkillTreeHotbar(event.getPlayer())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (astPlayer == null) {
            return;
        }
        SkillTreeNodeDefinition node = service.findTargetedNode(event.getPlayer()).orElse(null);
        if (node == null) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5828.getId()));
            return;
        }
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (service.unlockNode(astPlayer, node)) {
                event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5824.getId(), node.name()));
            } else {
                event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5825.getId()));
            }
            return;
        }
        if (service.relockNode(astPlayer, node)) {
            event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5826.getId(), node.name()));
        } else {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5827.getId()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (service.isSkillTreeSetupItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (service.isSkillTreeSetupItem(held)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHeldSlotChange(@NotNull PlayerItemHeldEvent event) {
        if (service.shouldSuppressSkillTreeSetupControls(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (!service.isPlayerModeSkillTree(event.getPlayer())) {
            return;
        }
        if (!service.shouldUseSkillTreeHotbar(event.getPlayer())) {
            return;
        }
        if (event.getNewSlot() != 0) {
            event.setCancelled(true);
            if (!service.handleSkillTreeHotbarControl(event.getPlayer(), event.getNewSlot())) {
                event.getPlayer().getInventory().setHeldItemSlot(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (service.shouldSuppressSkillTreeSetupControls(player)
                && event.getClickedInventory() == player.getInventory()
                && event.getSlot() >= 0
                && event.getSlot() <= 8) {
            event.setCancelled(true);
            return;
        }
        if (!service.isPlayerModeSkillTree(player)) {
            return;
        }
        if (!service.shouldUseSkillTreeHotbar(player)) {
            return;
        }
        if (event.getClickedInventory() == player.getInventory() && event.getSlot() >= 0 && event.getSlot() <= 8) {
            event.setCancelled(true);
            service.handleSkillTreeHotbarControl(player, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (event.getPlayer() instanceof org.bukkit.entity.Player player && service.isPlayerModeSkillTree(player)) {
            org.bukkit.Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> service.renderSkillTreeHotbar(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        if (service.isPlayerModeSkillTree(event.getPlayer())) {
            service.applySkillTreeHotbar(event.getPlayer());
        } else {
            service.restoreHotbar(event.getPlayer());
        }
    }

    private void handlePositionItem(@NotNull PlayerInteractEvent event, @NotNull String positionId) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Location location = placementLocation(event.getClickedBlock(), event.getBlockFace());
            service.registerPosition(positionId, location);
            event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5821.getId(), positionId));
            return;
        }
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            SkillTreePosition target = service.findTargetedPosition(event.getPlayer()).orElse(null);
            if (target != null && service.removePosition(target.positionId())) {
                event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5822.getId(), target.positionId()));
            }
        }
    }

    private void handleConnectorItem(@NotNull PlayerInteractEvent event) {
        SkillTreePosition target = service.findTargetedPosition(event.getPlayer()).orElse(null);
        if (target == null) {
            event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5823.getId()));
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            service.selectConnectorLeft(event.getPlayer().getUniqueId(), target.positionId());
            event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5830.getId(), target.positionId()));
            return;
        }
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            String left = service.consumeConnectorLeft(event.getPlayer().getUniqueId());
            if (left == null) {
                event.getPlayer().sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5831.getId()));
                return;
            }
            if (service.toggleConnection(left, target.positionId())) {
                event.getPlayer().sendMessage(PlayerMsgResource.format(PlayerMsgId.P_5832.getId(), left, target.positionId()));
            }
        }
    }

    @NotNull
    private Location placementLocation(@NotNull Block clicked, @NotNull BlockFace face) {
        return clicked.getRelative(face).getLocation();
    }
}
