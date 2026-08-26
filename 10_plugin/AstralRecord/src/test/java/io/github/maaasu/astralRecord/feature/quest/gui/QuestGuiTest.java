package io.github.maaasu.astralRecord.feature.quest.gui;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardEntry;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 同一ボードを表示中にクエスト状態を更新して再描画すると、表示中インベントリを維持したまま最新の状態表示へ更新する。
     */
    @Test
    void refreshesTheCurrentBoardWithoutReplacingItsInventory() {
        PluginMock plugin = PluginMock.builder().withPluginName("astralrecord").build();
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        QuestDefinition quest = new QuestDefinition(
            "quest-test",
            "テストクエスト",
            List.of(),
            Material.PAPER,
            QuestRepeatMode.ONCE,
            0L,
            QuestCompletionMode.NPC,
            null,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestBoardDefinition board = new QuestBoardDefinition(
            "board-test",
            "テストボード",
            List.of(new QuestBoardEntry("quest-test", 1, 0, null, null))
        );
        when(questService.findQuest("quest-test")).thenReturn(quest);
        when(questService.findBoard("board-test")).thenReturn(board);
        when(questService.displayState(astPlayer, quest))
            .thenReturn(QuestDisplayState.AVAILABLE)
            .thenReturn(QuestDisplayState.READY_TO_TURN_IN);
        QuestGui questGui = new QuestGui(plugin, questService);
        var player = server().addPlayer();

        questGui.openBoard(player, astPlayer, board, "npc-test");
        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertEquals(
            "状態: 受領可能",
            PlainTextComponentSerializer.plainText().serialize(inventory.getItem(10).getItemMeta().lore().getFirst())
        );

        assertTrue(questGui.refreshBoard(player, astPlayer, board.id()));

        assertSame(inventory, player.getOpenInventory().getTopInventory());
        assertEquals(
            "状態: 報告可能",
            PlainTextComponentSerializer.plainText().serialize(inventory.getItem(10).getItemMeta().lore().getFirst())
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 一覧では受領できない論理スロットだけを受領枠案内で埋め、slot 49には受領枠専用itemを置かず、共通ナビゲーションボタンを置く。
     */
    @Test
    void rendersQuestLimitGuidesOnlyInUnavailableListSlotsInsteadOfSlot49() {
        PluginMock plugin = PluginMock.builder().withPluginName("astralrecord").build();
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(questService.activeQuests(astPlayer)).thenReturn(List.of());
        when(questService.maxActiveQuests(astPlayer)).thenReturn(5);
        QuestGui questGui = new QuestGui(plugin, questService);
        var player = server().addPlayer();

        questGui.openList(player, astPlayer);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        for (int index = 0; index < 5; index++) {
            var availableSlot = inventory.getItem((index / 7 + 1) * 9 + index % 7 + 1);
            assertEquals(Material.AIR, availableSlot.getType());
        }
        for (int index = 5; index <= QuestGui.MAX_LOGICAL_SLOT; index++) {
            var guide = inventory.getItem((index / 7 + 1) * 9 + index % 7 + 1);
            assertEquals(Material.BOOK, guide.getType());
            assertEquals("受領枠を増やすには", PlainTextComponentSerializer.plainText().serialize(guide.getItemMeta().displayName()));
            assertEquals(
                "現在の受領枠: 5",
                PlainTextComponentSerializer.plainText().serialize(guide.getItemMeta().lore().getFirst())
            );
        }
        assertEquals(Material.SPECTRAL_ARROW, inventory.getItem(QuestGui.BACK_SLOT).getType());
        assertEquals(
            QuestGui.BACK_SLOT,
            ((GuiNavigationHolder) inventory.getHolder()).getBackSlot()
        );
    }
}
