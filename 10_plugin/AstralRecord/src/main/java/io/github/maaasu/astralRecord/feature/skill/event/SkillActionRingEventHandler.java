package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionConsumeService;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * 右クリック入力をアクションリング表示へ差し替えるイベントハンドラです。
 */
public final class SkillActionRingEventHandler extends AbstractEventHandler {
    private final SkillActionRingService actionRingService;
    private final InventoryService inventoryService;
    private final SkillTreeService skillTreeService;
    private final PlayerInteractionConsumeService interactionConsumeService;

    /**
     * ハンドラを生成します。
     *
     * @param actionRingService アクションリング表示サービス
     * @param interactionConsumeService コンテンツインタラクトの優先状態
     */
    public SkillActionRingEventHandler(
        @NotNull SkillActionRingService actionRingService,
        @NotNull InventoryService inventoryService,
        @NotNull SkillTreeService skillTreeService,
        @NotNull PlayerInteractionConsumeService interactionConsumeService
    ) {
        this.actionRingService = actionRingService;
        this.inventoryService = inventoryService;
        this.skillTreeService = skillTreeService;
        this.interactionConsumeService = interactionConsumeService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            Player player = event.getPlayer();
            Action action = event.getAction();
            boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            if ((isLeftClick || isRightClick) && skillTreeService.shouldSuppressSkillTreeSetupControls(player)) {
                event.setCancelled(true);
                actionRingService.close(player);
                return;
            }
            if (event.getHand() == EquipmentSlot.HAND
                && (isLeftClick || isRightClick)
                && player.getInventory().getHeldItemSlot() == 8) {
                event.setCancelled(true);
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (isRightClick && (interactionConsumeService.isConsumed(event) || hasInteractableBlock(event))) {
                actionRingService.close(player);
                return;
            }
            if (!actionRingService.isOpen(player)
                && event.getHand() == EquipmentSlot.HAND
                && isRightClick
                && isPlayerMode(astPlayer)
                && isWeapon(astPlayer)) {
                event.setCancelled(true);
                actionRingService.toggle(astPlayer);
                return;
            }
            if (!actionRingService.isOpen(player)) {
                return;
            }
            event.setCancelled(true);
            if (isRightClick) {
                if (isPlayerMode(astPlayer)) {
                    actionRingService.toggle(astPlayer);
                } else {
                    actionRingService.close(player);
                }
                return;
            }
            if (!isLeftClick) {
                return;
            }
            actionRingService.suppressAttack(player);
            if (isPlayerMode(astPlayer)) {
                actionRingService.activateSelected(astPlayer);
            }
        }, LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerAnimation(@NotNull PlayerAnimationEvent event) {
        runSafely(() -> {
            if (skillTreeService.shouldSuppressSkillTreeSetupControls(event.getPlayer())) {
                event.setCancelled(true);
                actionRingService.close(event.getPlayer());
                return;
            }
            if (!actionRingService.isOpen(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_animation");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        runSafely(() -> {
            if (skillTreeService.shouldSuppressSkillTreeSetupControls(event.getPlayer())) {
                event.setCancelled(true);
                actionRingService.close(event.getPlayer());
                return;
            }
            if (!actionRingService.isOpen(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_hotbar");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamageByEntityBeforeCombat(@NotNull EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }
            if (skillTreeService.shouldSuppressSkillTreeSetupControls(player)) {
                event.setDamage(0.0D);
                event.setCancelled(true);
                actionRingService.close(player);
                return;
            }
            if (!actionRingService.isOpen(player)) {
                return;
            }
            event.setDamage(0.0D);
            event.setCancelled(true);
        }, LogId.E_5802, event.getDamager().getName(), "skill_action_ring_damage_cancel");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }
            if (skillTreeService.shouldSuppressSkillTreeSetupControls(player)) {
                event.setCancelled(true);
                actionRingService.close(player);
                return;
            }
            if (!actionRingService.isOpen(player)) {
                return;
            }
            event.setCancelled(true);
            actionRingService.suppressAttack(player);
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (isPlayerMode(astPlayer)) {
                actionRingService.activateSelected(astPlayer);
            }
        }, LogId.E_5802, event.getDamager().getName(), "skill_action_ring_damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> actionRingService.close(event.getPlayer()),
            LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_quit");
    }

    private boolean isPlayerMode(AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private boolean hasInteractableBlock(@NotNull PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return false;
        }
        BlockType blockType = event.getClickedBlock().getType().asBlockType();
        return blockType != null && blockType.isInteractable();
    }

    private boolean isWeapon(@NotNull AstPlayer astPlayer) {
        ItemModel item = inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND);
        return item != null
            && ItemCategory.EQUIPMENT.getApiValue().equalsIgnoreCase(item.getCategory())
            && item.getEquipment() != null
            && item.getEquipment().getSlot() == ItemEquipmentSlot.WEAPON;
    }
}
