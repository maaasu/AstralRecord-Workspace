package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * オフハンド切替入力をアクションリング表示へ差し替えるイベントハンドラです。
 */
public final class SkillActionRingEventHandler extends AbstractEventHandler {
    private final SkillActionRingService actionRingService;
    private final ItemService itemService;

    /**
     * ハンドラを生成します。
     *
     * @param actionRingService アクションリング表示サービス
     * @param itemService 武器判定に使用するアイテムサービス
     */
    public SkillActionRingEventHandler(
        @NotNull SkillActionRingService actionRingService,
        @NotNull ItemService itemService
    ) {
        this.actionRingService = actionRingService;
        this.itemService = itemService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        runSafely(() -> {
            event.setCancelled(true);
            Player player = event.getPlayer();
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (!isPlayerMode(astPlayer)) {
                return;
            }
            if (actionRingService.isOpen(player)) {
                actionRingService.toggle(astPlayer);
                return;
            }
            if (!isWeapon(player.getInventory().getItemInMainHand())) {
                GuiSound.DENY.play(player);
                return;
            }
            actionRingService.toggle(astPlayer);
        }, LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_swap");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            Player player = event.getPlayer();
            Action action = event.getAction();
            boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            if (event.getHand() == EquipmentSlot.HAND
                && (isLeftClick || isRightClick)
                && player.getInventory().getHeldItemSlot() == 8) {
                event.setCancelled(true);
                return;
            }
            if (!actionRingService.isOpen(player)) {
                return;
            }
            event.setCancelled(true);
            if (isRightClick) {
                actionRingService.returnToSelecting(player);
                return;
            }
            if (!isLeftClick) {
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (isPlayerMode(astPlayer)) {
                actionRingService.activateSelected(astPlayer);
            }
        }, LogId.E_5802, event.getPlayer().getName(), "skill_action_ring_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        runSafely(() -> {
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
            if (!actionRingService.isOpen(player)) {
                return;
            }
            event.setCancelled(true);
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

    private boolean isWeapon(@NotNull ItemStack itemStack) {
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        ItemModel item = itemService.findLoadedById(itemId);
        if (item == null) {
            item = itemService.loadItem(itemId);
        }
        return item != null
            && ItemCategory.EQUIPMENT.getApiValue().equalsIgnoreCase(item.getCategory())
            && item.getEquipment() != null
            && item.getEquipment().getSlot() == ItemEquipmentSlot.WEAPON;
    }
}
