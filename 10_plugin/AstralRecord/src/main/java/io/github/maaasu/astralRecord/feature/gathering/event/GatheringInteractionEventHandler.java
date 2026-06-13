package io.github.maaasu.astralRecord.feature.gathering.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

public class GatheringInteractionEventHandler extends AbstractEventHandler {
    private final GatheringService gatheringService;

    public GatheringInteractionEventHandler(@NotNull GatheringService gatheringService) {
        this.gatheringService = gatheringService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (gatheringService.startMining(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
