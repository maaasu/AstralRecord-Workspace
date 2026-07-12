package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.gui.BossChallengeCancelGui;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * ボス挑戦中止装置のインタラクト、ドロップ操作、GUI 操作を処理します。
 */
public final class BossChallengeCancelEventHandler extends AbstractEventHandler {
    private final BossChallengeService service;
    private final BossChallengeCancelGui gui;

    public BossChallengeCancelEventHandler(
            @NotNull BossChallengeService service,
            @NotNull BossChallengeCancelGui gui
    ) {
        this.service = service;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()) {
            return;
        }
        UUID challengeId = service.resolveCancelInteraction(event.getRightClicked());
        if (challengeId == null) {
            return;
        }
        event.setCancelled(true);
        runSafely(
                () -> openForLeader(event.getPlayer(), challengeId),
                LogId.E_6501,
                event.getPlayer().getName()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        UUID challengeId = service.findNearbyCancelController(event.getPlayer());
        if (challengeId == null) {
            return;
        }
        event.setCancelled(true);
        runSafely(
                () -> openForLeader(event.getPlayer(), challengeId),
                LogId.E_6501,
                event.getPlayer().getName()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!gui.isInventory(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() != BossChallengeCancelGui.CANCEL_SLOT) {
            return;
        }
        UUID challengeId = gui.getChallengeId(event.getView().getTopInventory());
        if (challengeId == null) {
            return;
        }
        BossChallengeService.PlayerCancelResult result = service.stopChallengeForLeader(
                player.getUniqueId(),
                challengeId
        );
        notifyResult(player, result, challengeId);
        player.closeInventory();
    }

    private void openForLeader(@NotNull Player player, @NotNull UUID challengeId) {
        if (!service.isChallengeLeader(player.getUniqueId(), challengeId)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6526);
            return;
        }
        gui.open(player, challengeId);
    }

    private void notifyResult(
            @NotNull Player player,
            @NotNull BossChallengeService.PlayerCancelResult result,
            @NotNull UUID challengeId
    ) {
        switch (result) {
            case STOPPED -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6528);
            case NOT_LEADER -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6526);
            case NO_CHALLENGE -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6527);
        }
    }
}
