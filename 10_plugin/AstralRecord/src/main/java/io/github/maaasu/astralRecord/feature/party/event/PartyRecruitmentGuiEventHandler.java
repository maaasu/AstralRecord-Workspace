package io.github.maaasu.astralRecord.feature.party.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.party.gui.PartyBoardGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyRecruitmentMessageAnvilGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyRecruitmentSettingsGui;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * パーティー募集設定、募集内容入力、掲示板一覧のGUI操作を処理します。
 */
public final class PartyRecruitmentGuiEventHandler extends AbstractEventHandler {
    private final PartyService partyService;
    private final PartyGui partyGui;
    private final PartyRecruitmentSettingsGui settingsGui;
    private final PartyRecruitmentMessageAnvilGui messageAnvilGui;
    private final PartyBoardGui boardGui;
    private final InventoryService inventoryService;

    /**
     * パーティー募集GUIイベントハンドラを生成します。
     *
     * @param partyService パーティー状態を更新するサービス
     * @param partyGui 参加成功後に開くパーティーGUI
     * @param settingsGui リーダー向け募集設定GUI
     * @param messageAnvilGui 募集内容入力用金床GUI
     * @param boardGui 公開パーティー一覧GUI
     * @param inventoryService ホットバーショートカット処理用サービス
     */
    public PartyRecruitmentGuiEventHandler(
        @NotNull PartyService partyService,
        @NotNull PartyGui partyGui,
        @NotNull PartyRecruitmentSettingsGui settingsGui,
        @NotNull PartyRecruitmentMessageAnvilGui messageAnvilGui,
        @NotNull PartyBoardGui boardGui,
        @NotNull InventoryService inventoryService
    ) {
        this.partyService = partyService;
        this.partyGui = partyGui;
        this.settingsGui = settingsGui;
        this.messageAnvilGui = messageAnvilGui;
        this.boardGui = boardGui;
        this.inventoryService = inventoryService;
    }

    /**
     * 募集 GUI の操作を処理します。
     * 募集内容入力用の金床では、入力紙のクリックだけ取消済みイベントでも受け付けます。
     *
     * @param event Bukkit のインベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            boolean settings = settingsGui.isInventory(topInventory);
            boolean messageInput = messageAnvilGui.isInventory(topInventory);
            boolean board = boardGui.isInventory(topInventory);
            if (!settings && !messageInput && !board) {
                return;
            }
            if (event.isCancelled()
                && (!messageInput || event.getRawSlot() != PartyRecruitmentMessageAnvilGui.CONFIRM_SLOT)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!AccountModeGuard.isGameplayPlayer(player)) {
                player.closeInventory();
                return;
            }
            if (!messageInput && HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                return;
            }
            if (messageInput) {
                handleMessageInput(player, event);
            } else if (settings) {
                handleSettings(player, event.getRawSlot());
            } else {
                handleBoard(player, topInventory, event.getRawSlot());
            }
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_recruitment_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!settingsGui.isInventory(topInventory)
                && !messageAnvilGui.isInventory(topInventory)
                && !boardGui.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_6100, event.getWhoClicked().getName(), "party_recruitment_gui_drag");
    }

    /**
     * 募集内容入力用の金床から結果アイテムを除き、通常の金床コスト表示と経験値処理を発生させないようにします。
     *
     * @param event Bukkit の金床結果準備イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareAnvil(@NotNull PrepareAnvilEvent event) {
        runSafely(() -> {
            if (!messageAnvilGui.isInventory(event.getInventory())) {
                return;
            }
            if (event.getView() instanceof AnvilView anvilView) {
                anvilView.setRepairCost(0);
            }
            event.setResult(null);
        }, LogId.E_6100, "anvil", "party_recruitment_gui_prepare");
    }

    private void handleSettings(@NotNull Player player, int rawSlot) {
        if (rawSlot == PartyRecruitmentSettingsGui.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        Party party = partyService.findParty(player.getUniqueId());
        if (astPlayer == null || party == null || !party.isLeader(player.getUniqueId())) {
            GuiSound.DENY.play(player);
            partyGui.open(player);
            return;
        }
        if (rawSlot == PartyRecruitmentSettingsGui.MESSAGE_SLOT) {
            GuiSound.OPEN.play(player);
            messageAnvilGui.open(player, party.getRecruitmentMessage());
            return;
        }
        PartyActionResult result;
        if (rawSlot == PartyRecruitmentSettingsGui.APPROVAL_SLOT) {
            result = partyService.toggleRecruitmentApproval(astPlayer);
        } else if (rawSlot == PartyRecruitmentSettingsGui.PUBLISH_SLOT) {
            result = party.isRecruitmentPublished()
                ? partyService.unpublishRecruitment(astPlayer)
                : partyService.publishRecruitment(astPlayer);
        } else {
            GuiSound.DENY.play(player);
            return;
        }
        sendResult(player, result);
        playResultSound(player, result);
        settingsGui.open(player);
    }

    /**
     * 入力紙のクリックで金床の名前入力値を募集内容として確定します。
     *
     * @param player 募集内容を設定するプレイヤー
     * @param event 金床GUIのクリックイベント
     */
    private void handleMessageInput(@NotNull Player player, @NotNull InventoryClickEvent event) {
        if (event.getRawSlot() != PartyRecruitmentMessageAnvilGui.CONFIRM_SLOT) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !(event.getView() instanceof AnvilView anvilView)) {
            GuiSound.DENY.play(player);
            return;
        }
        String renameText = anvilView.getRenameText();
        PartyActionResult result = partyService.setRecruitmentMessage(
            astPlayer,
            renameText == null ? "" : renameText
        );
        sendResult(player, result);
        playResultSound(player, result);
        if (result.success()) {
            settingsGui.open(player);
        }
    }

    private void handleBoard(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        int pageIndex = boardGui.getPageIndex(inventory);
        if (rawSlot == PagedGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        if (rawSlot == PagedGuiView.PREVIOUS_SLOT) {
            GuiSound.SELECT.play(player);
            boardGui.open(player, pageIndex - 1);
            return;
        }
        if (rawSlot == PagedGuiView.NEXT_SLOT) {
            GuiSound.SELECT.play(player);
            boardGui.open(player, pageIndex + 1);
            return;
        }
        UUID partyId = boardGui.getPartyId(inventory, rawSlot);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (partyId == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        PartyActionResult result = partyService.joinFromBoard(astPlayer, partyId);
        sendResult(player, result);
        playResultSound(player, result);
        if (result.success() && partyService.findParty(player.getUniqueId()) != null) {
            partyGui.open(player);
        } else {
            boardGui.open(player, pageIndex);
        }
    }

    private void sendResult(@NotNull Player player, @NotNull PartyActionResult result) {
        PlayerMessageService.getInstance().send(player, result.messageId(), result.args());
    }

    private void playResultSound(@NotNull Player player, @NotNull PartyActionResult result) {
        if (result.success()) {
            GuiSound.SUCCESS.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
    }
}
