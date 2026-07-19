package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 共通プレイヤー一覧 GUI と詳細 GUI の操作を処理します。
 */
public final class PlayerBrowserGuiEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final PlayerListGui playerListGui;
    private final PlayerDetailGui playerDetailGui;
    private final PartyService partyService;
    private final StatusService statusService;
    private final InventoryService inventoryService;
    private final TradeService tradeService;
    private final CurrencyService currencyService;
    private final PlayerClassService playerClassService;

    public PlayerBrowserGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull PlayerListGui playerListGui,
        @NotNull PlayerDetailGui playerDetailGui,
        @NotNull PartyService partyService,
        @NotNull StatusService statusService,
        @NotNull InventoryService inventoryService
    ) {
        this(
            plugin,
            playerListGui,
            playerDetailGui,
            partyService,
            statusService,
            inventoryService,
            null,
            null,
            null
        );
    }

    public PlayerBrowserGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull PlayerListGui playerListGui,
        @NotNull PlayerDetailGui playerDetailGui,
        @NotNull PartyService partyService,
        @NotNull StatusService statusService,
        @NotNull InventoryService inventoryService,
        @Nullable TradeService tradeService,
        @Nullable CurrencyService currencyService,
        @Nullable PlayerClassService playerClassService
    ) {
        this.plugin = plugin;
        this.playerListGui = playerListGui;
        this.playerDetailGui = playerDetailGui;
        this.partyService = partyService;
        this.statusService = statusService;
        this.inventoryService = inventoryService;
        this.tradeService = tradeService;
        this.currencyService = currencyService;
        this.playerClassService = playerClassService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (playerListGui.isInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                        return;
                    }
                    handlePlayerListClick(player, event.getRawSlot(), event.getView().getTopInventory());
                }
                return;
            }
            if (!playerDetailGui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                    return;
                }
                handlePlayerDetailClick(player, event.getRawSlot(), event.getView().getTopInventory());
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "player_browser_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!playerListGui.isInventory(event.getView().getTopInventory())
                && !playerDetailGui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "player_browser_gui_drag");
    }

    private void handlePlayerListClick(@NotNull Player player, int rawSlot, @NotNull org.bukkit.inventory.Inventory inventory) {
        if (rawSlot == PlayerListGui.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            plugin.getGuiNavigationService().openPrevious(player);
            return;
        }

        int pageIndex = playerListGui.getPageIndex(inventory);
        if (rawSlot == PlayerListGui.PREVIOUS_SLOT && playerListGui.hasPreviousPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            reopenList(player, inventory, pageIndex - 1);
            return;
        }
        if (rawSlot == PlayerListGui.NEXT_SLOT && playerListGui.hasNextPage(inventory)) {
            GuiSound.SELECT.play(player);
            reopenList(player, inventory, pageIndex + 1);
            return;
        }

        UUID targetId = playerListGui.getPlayerId(inventory, rawSlot);
        if (targetId == null) {
            GuiSound.DENY.play(player);
            return;
        }

        PlayerListPurpose purpose = playerListGui.getPurpose(inventory);
        if (purpose == PlayerListPurpose.PARTY_INVITE) {
            handleInvite(player, targetId, pageIndex);
            return;
        }
        if (purpose == PlayerListPurpose.PLAYER_INFO) {
            handleOpenDetail(player, targetId, pageIndex);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handlePlayerDetailClick(@NotNull Player player, int rawSlot, @NotNull org.bukkit.inventory.Inventory inventory) {
        if (rawSlot == PlayerDetailGui.BUFF_SLOT) {
            UUID targetId = playerDetailGui.getTargetId(inventory);
            Player targetPlayer = targetId == null ? null : Bukkit.getPlayer(targetId);
            AstPlayer target = targetPlayer == null ? null : AstPlayerCache.get(targetPlayer);
            if (target == null) {
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5603,
                    targetId == null ? "unknown" : playerName(targetId)
                );
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            plugin.getMenuView().openBuff(player, targetId, statusService.getActiveBuffs(target));
            return;
        }
        if (rawSlot == PlayerDetailGui.TRADE_SLOT) {
            handleTradeRequest(player, inventory);
            return;
        }
        if (rawSlot == PlayerDetailGui.PARTY_INVITE_SLOT) {
            handleDetailInvite(player, inventory);
            return;
        }
        if (rawSlot == BaseMenuScreenView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            plugin.getGuiNavigationService().openPrevious(player);
            return;
        }

        GuiSound.DENY.play(player);
    }

    private void handleInvite(@NotNull Player player, @NotNull UUID targetId, int pageIndex) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        Player target = Bukkit.getPlayer(targetId);
        if (astPlayer == null || target == null) {
            GuiSound.DENY.play(player);
            return;
        }
        PartyActionResult result = partyService.invite(astPlayer, target);
        PlayerMessageService.getInstance().send(player, result.messageId(), result.args());
        if (result.success()) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        openInviteList(player, pageIndex);
    }

    private void handleOpenDetail(
        @NotNull Player viewer,
        @NotNull UUID targetId,
        int pageIndex
    ) {
        Player targetPlayer = Bukkit.getPlayer(targetId);
        AstPlayer target = targetPlayer == null ? null : AstPlayerCache.get(targetPlayer);
        if (target == null) {
            PlayerMessageService.getInstance().send(viewer, PlayerMsgId.P_5603, playerName(targetId));
            GuiSound.DENY.play(viewer);
            MenuOpenEventHandler.suppressNextCloseSound(viewer);
            openInfoList(viewer, pageIndex);
            return;
        }
        GuiSound.SELECT.play(viewer);
        MenuOpenEventHandler.suppressNextCloseSound(viewer);
        openTargetDetail(viewer, target);
    }

    private void handleTradeRequest(@NotNull Player player, @NotNull org.bukkit.inventory.Inventory inventory) {
        UUID targetId = playerDetailGui.getTargetId(inventory);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, targetId == null ? "unknown" : playerName(targetId));
            GuiSound.DENY.play(player);
            return;
        }
        if (player.getUniqueId().equals(target.getUniqueId()) || tradeService == null) {
            GuiSound.DENY.play(player);
            return;
        }
        tradeService.requestTrade(player, target);
        GuiSound.SELECT.play(player);
    }

    private void handleDetailInvite(@NotNull Player player, @NotNull org.bukkit.inventory.Inventory inventory) {
        UUID targetId = playerDetailGui.getTargetId(inventory);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (target == null || astPlayer == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, targetId == null ? "unknown" : playerName(targetId));
            GuiSound.DENY.play(player);
            return;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            GuiSound.DENY.play(player);
            return;
        }
        PartyActionResult result = partyService.invite(astPlayer, target);
        PlayerMessageService.getInstance().send(player, result.messageId(), result.args());
        if (result.success()) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
    }

    private void reopenList(@NotNull Player player, @NotNull org.bukkit.inventory.Inventory inventory, int pageIndex) {
        MenuOpenEventHandler.suppressNextCloseSound(player);
        PlayerListPurpose purpose = playerListGui.getPurpose(inventory);
        if (purpose == PlayerListPurpose.PARTY_INVITE) {
            openInviteList(player, pageIndex);
            return;
        }
        openInfoList(player, pageIndex);
    }

    public void openInfoList(@NotNull Player player, int pageIndex) {
        List<UUID> candidateIds = Bukkit.getOnlinePlayers().stream()
            .map(Player::getUniqueId)
            .toList();
        playerListGui.open(
            player,
            PlayerListPurpose.PLAYER_INFO,
            "プレイヤー一覧",
            candidateIds,
            pageIndex
        );
    }

    public void openInviteList(@NotNull Player player, int pageIndex) {
        Party party = partyService.findParty(player.getUniqueId());
        if (party == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5902);
            GuiSound.DENY.play(player);
            return;
        }
        List<UUID> candidateIds = Bukkit.getOnlinePlayers().stream()
            .filter(online -> !online.getUniqueId().equals(player.getUniqueId()))
            .map(Player::getUniqueId)
            .filter(playerId -> !party.contains(playerId))
            .toList();
        playerListGui.open(
            player,
            PlayerListPurpose.PARTY_INVITE,
            "招待プレイヤー",
            candidateIds,
            pageIndex
        );
    }

    public void openDetailFromCommand(@NotNull Player viewer, @NotNull Player targetPlayer) {
        AstPlayer target = AstPlayerCache.get(targetPlayer);
        if (target == null) {
            PlayerMessageService.getInstance().send(viewer, PlayerMsgId.P_5603, targetPlayer.getName());
            GuiSound.DENY.play(viewer);
            return;
        }
        GuiSound.OPEN.play(viewer);
        openTargetDetail(viewer, target);
    }

    /**
     * 閲覧者自身の統合プレイヤー情報 GUI を開きます。
     *
     * @param viewer 表示対象プレイヤー
     */
    public void openSelfDetail(@NotNull Player viewer) {
        AstPlayer target = AstPlayerCache.get(viewer);
        if (target == null) {
            GuiSound.DENY.play(viewer);
            return;
        }
        openTargetDetail(viewer, target);
    }

    private void openTargetDetail(
        @NotNull Player viewer,
        @NotNull AstPlayer target
    ) {
        long goldAmount = currencyService == null
            ? 0L
            : currencyService.getGoldAmount(target.getAccount().getUuid());
        String classDisplayName = playerClassService == null
            ? target.getClassId()
            : playerClassService.getDisplayName(target.getClassId());
        var classProgresses = playerClassService == null
            ? List.<io.github.maaasu.astralRecord.feature.playerclass.model.ClassProgressViewEntry>of()
            : playerClassService.getClassProgressViewEntries(target);
        playerDetailGui.open(
            viewer,
            target,
            statusService.refreshStatus(target),
            goldAmount,
            classDisplayName,
            classProgresses
        );
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        return Objects.requireNonNullElse(Bukkit.getOfflinePlayer(playerId).getName(), playerId.toString());
    }
}
