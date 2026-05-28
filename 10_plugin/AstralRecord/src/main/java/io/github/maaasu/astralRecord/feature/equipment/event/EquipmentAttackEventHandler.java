package io.github.maaasu.astralRecord.feature.equipment.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.equipment.service.EquipmentAttackService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * equipment の左クリック通常攻撃を処理するイベントハンドラです。
 */
public final class EquipmentAttackEventHandler extends AbstractEventHandler {

    private final EquipmentAttackService equipmentAttackService;

    public EquipmentAttackEventHandler(@NotNull EquipmentAttackService equipmentAttackService) {
        this.equipmentAttackService = equipmentAttackService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            Action action = event.getAction();
            if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
                return;
            }

            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null || astPlayer.getAccount().getMode() != AccountMode.PLAYER) {
                return;
            }

            equipmentAttackService.handleLeftClick(astPlayer, event.getItem(), event.getPlayer().getEyeLocation());
        }, LogId.E_6000, event.getPlayer().getName());
    }
}
