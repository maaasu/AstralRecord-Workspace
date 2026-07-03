package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * スキルツリー管理アイテムとプレイヤー操作のイベントを扱います。
 */
public class SkillTreeEventHandler extends AbstractEventHandler {
    private static final long RIGHT_CLICK_LEFT_SUPPRESS_MILLIS = 250L;

    private final SkillTreeService service;
    private final Map<UUID, Long> suppressLeftClickUntilMillis = new HashMap<>();

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
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5719);
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
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (astPlayer == null) {
            return;
        }
        service.preloadState(astPlayer);
        if (!service.isStateReady(astPlayer)) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5836);
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)
                && isLeftClickSuppressed(playerId)) {
            return;
        }
        SkillTreeNodeDefinition node = service.findTargetedNode(event.getPlayer()).orElse(null);
        if (node == null) {
            playDenied(event.getPlayer(), 0.85F);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5828);
            return;
        }
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (service.isNodeUnlocked(astPlayer, node)) {
                playDenied(event.getPlayer(), 1.15F);
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5838);
                return;
            }
            if (!service.hasAvailableUnlockPoint(astPlayer)) {
                playDenied(event.getPlayer(), 0.65F);
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5839);
                return;
            }
            if (!service.canUnlockNode(astPlayer, node)) {
                playDenied(event.getPlayer(), 0.75F);
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5825);
                return;
            }
            if (service.unlockNode(astPlayer, node)) {
                playUnlock(event.getPlayer());
                PlayerMessageService.getInstance().send(
                    event.getPlayer(),
                    PlayerMsgId.P_5824,
                    ColorCodeUtil.toLegacyText(node.name(), node.id())
                );
            } else {
                playDenied(event.getPlayer(), 0.75F);
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5825);
            }
            return;
        }
        if (!service.isNodeUnlocked(astPlayer, node)) {
            playDenied(event.getPlayer(), 1.0F);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5840);
            return;
        }
        if (!service.canAffordRelock(astPlayer)) {
            playDenied(event.getPlayer(), 0.6F);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5841);
            return;
        }
        if (service.relockNode(astPlayer, node)) {
            playRelock(event.getPlayer());
            PlayerMessageService.getInstance().send(
                event.getPlayer(),
                PlayerMsgId.P_5826,
                ColorCodeUtil.toLegacyText(node.name(), node.id())
            );
        } else {
            playDenied(event.getPlayer(), 0.75F);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5827);
        }
        suppressLeftClickUntilMillis.put(playerId, System.currentTimeMillis() + RIGHT_CLICK_LEFT_SUPPRESS_MILLIS);
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
            org.bukkit.Bukkit.getScheduler().runTask(
                    io.github.maaasu.astralRecord.AstralRecord.getInstance(),
                    () -> event.getPlayer().getInventory().setHeldItemSlot(event.getPreviousSlot())
            );
            return;
        }
        if (!service.isPlayerModeSkillTree(event.getPlayer())) {
            return;
        }
        service.markViewerContextDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (service.shouldSuppressSkillTreeSetupControls(player)
                && event.getClickedInventory() == player.getInventory()
                && event.getSlot() >= 0
                && event.getSlot() <= 8) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
        if (service.isPlayerModeSkillTree(event.getPlayer())) {
            service.markViewerContextDirty(event.getPlayer());
            return;
        }
        service.clearPlayerPresentation(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        if (!shouldRefreshSkillTreeVisuals(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()
                || Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0) {
            service.markViewerContextDirty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
        service.markViewerContextDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        service.restorePlayerVisibility(event.getPlayer());
        service.clearPlayerPresentation(event.getPlayer());
    }

    private void handlePositionItem(@NotNull PlayerInteractEvent event, @NotNull String positionId) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Location location = placementLocation(event.getClickedBlock(), event.getBlockFace());
            service.registerPosition(positionId, location);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5821, positionId);
            return;
        }
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            SkillTreePosition target = service.findTargetedPosition(event.getPlayer()).orElse(null);
            if (target != null && service.removePosition(target.positionId())) {
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5822, target.positionId());
            }
        }
    }

    private void handleConnectorItem(@NotNull PlayerInteractEvent event) {
        SkillTreePosition target = service.findTargetedPosition(event.getPlayer()).orElse(null);
        if (target == null) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5823);
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            service.selectConnectorLeft(event.getPlayer().getUniqueId(), target.positionId());
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5830, target.positionId());
            return;
        }
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            String left = service.consumeConnectorLeft(event.getPlayer().getUniqueId());
            if (left == null) {
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5831);
                return;
            }
            if (service.toggleConnection(left, target.positionId())) {
                PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5832, left, target.positionId());
            }
        }
    }

    @NotNull
    private Location placementLocation(@NotNull Block clicked, @NotNull BlockFace face) {
        return clicked.getRelative(face).getLocation();
    }

    private void playUnlock(@NotNull org.bukkit.entity.Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.45F, 1.45F);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.35F, 1.8F);
    }

    private void playRelock(@NotNull org.bukkit.entity.Player player) {
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, SoundCategory.PLAYERS, 0.55F, 1.2F);
    }

    private void playDenied(@NotNull org.bukkit.entity.Player player, float pitch) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.45F, pitch);
    }

    private boolean shouldRefreshSkillTreeVisuals(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return service.isAdminMode(astPlayer) || service.isPlayerModeSkillTree(player);
    }

    private boolean isLeftClickSuppressed(@NotNull UUID playerId) {
        Long until = suppressLeftClickUntilMillis.get(playerId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() <= until) {
            return true;
        }
        suppressLeftClickUntilMillis.remove(playerId);
        return false;
    }
}
