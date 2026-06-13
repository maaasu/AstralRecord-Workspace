package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * weapon equipment の左右クリック攻撃を処理するイベントハンドラです。
 */
public final class ItemWeaponAttackEventHandler extends AbstractEventHandler {
    private final ItemWeaponAttackService itemWeaponAttackService;
    private final SkillActionRingService actionRingService;
    private final SkillTreeService skillTreeService;
    private final MobService mobService;

    public ItemWeaponAttackEventHandler(
        @NotNull ItemWeaponAttackService itemWeaponAttackService,
        @NotNull SkillActionRingService actionRingService,
        @NotNull SkillTreeService skillTreeService,
        @NotNull MobService mobService
    ) {
        this.itemWeaponAttackService = itemWeaponAttackService;
        this.actionRingService = actionRingService;
        this.skillTreeService = skillTreeService;
        this.mobService = mobService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            Action action = event.getAction();
            boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            if (!isLeftClick && !isRightClick) {
                return;
            }
            if (mobService.findTargetedNpc(
                    event.getPlayer(),
                    MobService.NPC_INTERACTION_DISTANCE,
                    MobService.NPC_INTERACTION_RAY_SIZE
            ) != null) {
                event.setCancelled(true);
                return;
            }
            if (skillTreeService.isSkillTreeEditing(event.getPlayer())) {
                event.setCancelled(true);
                return;
            }
            if (actionRingService.isAttackSuppressed(event.getPlayer())) {
                return;
            }
            if (actionRingService.isOpen(event.getPlayer())) {
                return;
            }

            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null || astPlayer.getAccount().getMode() != AccountMode.PLAYER) {
                return;
            }

            if (isLeftClick) {
                itemWeaponAttackService.handleLeftClick(astPlayer, event.getPlayer().getEyeLocation());
                return;
            }

            itemWeaponAttackService.handleRightClick(astPlayer, event.getPlayer().getEyeLocation());
        }, LogId.E_6000, event.getPlayer().getName());
    }
}
