package io.github.maaasu.astralRecord.feature.quest.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.gui.QuestGui;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class QuestGuiEventHandler extends AbstractEventHandler {
    private final QuestGui questGui;
    private final QuestService questService;
    private final InventoryService inventoryService;

    public QuestGuiEventHandler(@NotNull QuestGui questGui, @NotNull QuestService questService, @NotNull InventoryService inventoryService) {
        this.questGui = questGui;
        this.questService = questService;
        this.inventoryService = inventoryService;
    }

    public void openBoard(@NotNull Player player, @NotNull String boardId, @Nullable String npcId) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        QuestBoardDefinition board = questService.findBoard(boardId);
        if (astPlayer == null || board == null || !AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        questGui.openBoard(player, astPlayer, board, npcId);
        GuiSound.OPEN.play(player);
    }

    public void openList(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        questGui.openList(player, astPlayer);
        GuiSound.OPEN.play(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (questGui.isBoardInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    handleBoardClick(event, player);
                }
                return;
            }
            if (questGui.isListInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    handleListClick(event, player);
                }
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "quest_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (questGui.isBoardInventory(event.getView().getTopInventory()) || questGui.isListInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    private void handleBoardClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        String boardId = questGui.getBoardId(event.getView().getTopInventory());
        QuestBoardDefinition board = boardId == null ? null : questService.findBoard(boardId);
        if (astPlayer == null || board == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int pageIndex = questGui.getPageIndex(event.getView().getTopInventory());
        if (event.getRawSlot() == QuestGui.PREVIOUS_PAGE_SLOT && questGui.hasPreviousPage(pageIndex)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            questGui.openBoard(player, astPlayer, board, questGui.getNpcId(event.getView().getTopInventory()), pageIndex - 1);
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() == QuestGui.NEXT_PAGE_SLOT && questGui.hasNextPage(board, pageIndex)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            questGui.openBoard(player, astPlayer, board, questGui.getNpcId(event.getView().getTopInventory()), pageIndex + 1);
            GuiSound.SELECT.play(player);
            return;
        }
        String questId = questGui.getQuestId(event.getCurrentItem());
        QuestDefinition quest = questId == null ? null : questService.findQuest(questId);
        if (quest == null) {
            GuiSound.DENY.play(player);
            return;
        }
        QuestDisplayState state = questService.displayState(astPlayer, quest);
        boolean changed = switch (state) {
            case AVAILABLE -> questService.accept(astPlayer, quest, questGui.getNpcId(event.getView().getTopInventory()));
            case READY_TO_TURN_IN -> questService.turnIn(astPlayer, quest, questGui.getNpcId(event.getView().getTopInventory()));
            default -> false;
        };
        MenuOpenEventHandler.suppressNextCloseSound(player);
        questGui.openBoard(player, astPlayer, board, questGui.getNpcId(event.getView().getTopInventory()), pageIndex);
        (changed ? GuiSound.SELECT : GuiSound.DENY).play(player);
    }

    private void handleListClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        String questId = questGui.getQuestId(event.getCurrentItem());
        if (astPlayer == null || questId == null || !questService.abandon(astPlayer, questId)) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        questGui.openList(player, astPlayer);
        GuiSound.SELECT.play(player);
    }
}
