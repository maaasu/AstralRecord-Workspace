package io.github.maaasu.astralRecord.feature.quest.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.gui.QuestGui;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 一覧の通常クリックはクエストを破棄しない。
     */
    @Test
    void doesNotAbandonQuestOnOrdinaryLeftClickInList() {
        TestContext context = new TestContext(ClickType.LEFT);

        context.invokeClick();

        verify(context.event).setCancelled(true);
        verify(context.questService, never()).abandon(context.astPlayer, context.questId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 一覧の通常ドロップはクエストを破棄して一覧を再描画する。
     */
    @Test
    void abandonsQuestOnDropInList() {
        TestContext context = new TestContext(ClickType.DROP);

        context.invokeClick();

        verify(context.questService).abandon(context.astPlayer, context.questId);
        verify(context.questGui).openList(context.player, context.astPlayer);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 一覧のCtrl+ドロップもクエストを破棄する。
     */
    @Test
    void abandonsQuestOnControlDropInList() {
        TestContext context = new TestContext(ClickType.CONTROL_DROP);

        context.invokeClick();

        verify(context.questService).abandon(context.astPlayer, context.questId);
        verify(context.questGui).openList(context.player, context.astPlayer);
    }

    private static final class TestContext {
        private final QuestGui questGui = mock(QuestGui.class);
        private final QuestService questService = mock(QuestService.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final Inventory topInventory = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final ItemStack questItem = mock(ItemStack.class);
        private final InventoryClickEvent event = mock(InventoryClickEvent.class);
        private final QuestGuiEventHandler handler = new QuestGuiEventHandler(questGui, questService, inventoryService);
        private final String questId = "quest-test";

        private TestContext(ClickType click) {
            when(view.getTopInventory()).thenReturn(topInventory);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getCurrentItem()).thenReturn(questItem);
            when(event.getClick()).thenReturn(click);
            when(player.getName()).thenReturn("tester");
            when(questGui.isListInventory(topInventory)).thenReturn(true);
            when(questGui.getQuestId(questItem)).thenReturn(questId);
            when(questService.abandon(astPlayer, questId)).thenReturn(true);
        }

        private void invokeClick() {
            try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
                handler.onInventoryClick(event);
            }
        }
    }
}
