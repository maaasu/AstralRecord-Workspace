package io.github.maaasu.astralRecord.feature.party.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyMemberActionGui;
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
 * パーティー GUI とメンバー操作 GUI のクリックを処理します。
 */
public final class PartyGuiEventHandler extends AbstractEventHandler {
    private final PartyGui gui;
    private final PartyMemberActionGui memberActionGui;
    private final PartyService partyService;
    private final MenuView menuView;
    private final PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler;

    public PartyGuiEventHandler(
        @NotNull PartyGui gui,
        @NotNull PartyMemberActionGui memberActionGui,
        @NotNull PartyService partyService,
        @NotNull MenuView menuView,
        @NotNull PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler
    ) {
        this.gui = gui;
        this.memberActionGui = memberActionGui;
        this.partyService = partyService;
        this.menuView = menuView;
        this.playerBrowserGuiEventHandler = playerBrowserGuiEventHandler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            boolean isPartyGui = gui.isInventory(event.getView().getTopInventory());
            boolean isMemberActionGui = memberActionGui.isInventory(event.getView().getTopInventory());
            if (!isPartyGui && !isMemberActionGui) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (isMemberActionGui) {
                handleMemberActionClick(player, event.getRawSlot());
                return;
            }
            handleClick(player, event.getRawSlot());
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())
                && !memberActionGui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_gui_drag");
    }

    private void handleClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == PartyGui.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
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
        if (rawSlot == PartyGui.INVITE_SLOT && party.isLeader(player.getUniqueId())) {
            GuiSound.OPEN.play(player);
            playerBrowserGuiEventHandler.openInviteList(player, 0);
            return;
        }
        if (gui.isMemberSlot(rawSlot)) {
            UUID memberId = gui.getMemberId(player.getOpenInventory().getTopInventory(), rawSlot);
            if (memberId != null && !memberId.equals(player.getUniqueId()) && party.isLeader(player.getUniqueId())) {
                GuiSound.SELECT.play(player);
                memberActionGui.open(player, memberId);
                return;
            }
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

    private void handleMemberActionClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == PartyMemberActionGui.BACK_TO_PARTY_SLOT) {
            GuiSound.SELECT.play(player);
            gui.open(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        UUID targetId = memberActionGui.getTargetId(player.getOpenInventory().getTopInventory());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (astPlayer == null || target == null) {
            GuiSound.DENY.play(player);
            return;
        }

        PartyActionResult result;
        if (rawSlot == PartyMemberActionGui.PROMOTE_SLOT) {
            result = partyService.promote(astPlayer, target);
        } else if (rawSlot == PartyMemberActionGui.KICK_SLOT) {
            result = partyService.kick(astPlayer, target);
        } else {
            GuiSound.DENY.play(player);
            return;
        }
        sendResult(player, result);
        playResultSound(player, result);
        gui.open(player);
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
