package io.github.maaasu.astralRecord.shared.interaction;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * エンティティへの右クリックを武器の汎用右クリックより優先する共通入口です。
 */
public final class PlayerInteractionPriorityEventHandler extends AbstractEventHandler {
    private final PlayerInteractionConsumeService consumeService;

    /**
     * 共通インタラクト優先ハンドラを生成します。
     *
     * @param consumeService インタラクト消費状態の共有サービス
     */
    public PlayerInteractionPriorityEventHandler(@NotNull PlayerInteractionConsumeService consumeService) {
        this.consumeService = consumeService;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        runSafely(() -> {
            if (event.getHand() == EquipmentSlot.HAND) {
                consumeService.prioritize(event.getPlayer());
            }
        }, LogId.E_5802, event.getPlayer().getName(), "interaction_priority_entity");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        consumeService.clear(event.getPlayer());
    }
}
