package io.github.maaasu.astralRecord.feature.party.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * パーティー GUI のクリック操作を処理します。
 */
public final class PartyGuiEventHandler extends AbstractEventHandler {
    private final PartyGui gui;
    private final PartyService partyService;

    public PartyGuiEventHandler(@NotNull PartyGui gui, @NotNull PartyService partyService) {
        this.gui = gui;
        this.partyService = partyService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleClick(player, event.getRawSlot());
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_gui_drag");
    }

    private void handleClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == PartyGui.CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        Party party = partyService.findParty(player.getUniqueId());
        if (party == null) {
            handleNoPartyClick(player, astPlayer, rawSlot);
            return;
        }
        if (rawSlot == PartyGui.LEAVE_OR_DISBAND_SLOT) {
            PartyActionResult result = party.isLeader(player.getUniqueId())
                ? partyService.disband(astPlayer)
                : partyService.leave(astPlayer);
            sendResult(player, result);
            playResultSound(player, result);
            gui.open(player);
            return;
        }

        GuiSound.DENY.play(player);
    }

    private void handleNoPartyClick(@NotNull Player player, @NotNull AstPlayer astPlayer, int rawSlot) {
        if (rawSlot == PartyGui.CREATE_SLOT) {
            PartyActionResult result = partyService.createParty(astPlayer);
            sendResult(player, result);
            playResultSound(player, result);
            gui.open(player);
            return;
        }

        UUID leaderId = gui.getInviteLeaderId(player.getOpenInventory().getTopInventory(), rawSlot);
        if (leaderId != null) {
            Player leader = Bukkit.getPlayer(leaderId);
            if (leader == null) {
                GuiSound.DENY.play(player);
                return;
            }
            PartyActionResult result = partyService.acceptInvite(astPlayer, leader.getName());
            sendResult(player, result);
            playResultSound(player, result);
            gui.open(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void sendResult(@NotNull Player player, @NotNull PartyActionResult result) {
        player.sendMessage(PlayerMsgResource.format(result.messageId().getId(), result.args()));
    }

    private void playResultSound(@NotNull Player player, @NotNull PartyActionResult result) {
        if (result.success()) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
    }
}
