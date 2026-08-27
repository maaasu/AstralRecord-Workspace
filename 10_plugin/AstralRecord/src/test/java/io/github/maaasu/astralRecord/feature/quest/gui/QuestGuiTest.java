package io.github.maaasu.astralRecord.feature.quest.gui;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardEntry;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
            assertEquals("クエスト受領枠を増やすには", PlainTextComponentSerializer.plainText().serialize(guide.getItemMeta().displayName()));
            assertEquals(
                "現在の受領枠: 5件",
                PlainTextComponentSerializer.plainText().serialize(guide.getItemMeta().lore().getFirst())
            );
            assertEquals(
                "クエスト受領上限を増やすと",
                PlainTextComponentSerializer.plainText().serialize(guide.getItemMeta().lore().get(1))
            );
        }
        assertEquals(Material.SPECTRAL_ARROW, inventory.getItem(QuestGui.BACK_SLOT).getType());
        assertEquals(
            QuestGui.BACK_SLOT,
            ((GuiNavigationHolder) inventory.getHolder()).getBackSlot()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: クエストGUIの防具アイコンは、クエスト固有の表示を維持したままバニラの装備時ツールチップを表示しない。
     */
    @Test
    void hidesVanillaTooltipForArmorQuestIcons() {
        PluginMock plugin = PluginMock.builder().withPluginName("astralrecord").build();
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        QuestDefinition quest = new QuestDefinition(
            "quest-armor",
            "防具クエスト",
            List.of("クエスト説明"),
            Material.TURTLE_HELMET,
            QuestRepeatMode.ONCE,
            0L,
            QuestCompletionMode.NPC,
            null,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestBoardDefinition board = new QuestBoardDefinition(
            "board-armor",
            "防具ボード",
            List.of(new QuestBoardEntry("quest-armor", 1, 0, null, null))
        );
        when(questService.findQuest("quest-armor")).thenReturn(quest);
        when(questService.displayState(astPlayer, quest)).thenReturn(QuestDisplayState.AVAILABLE);

        QuestGui questGui = new QuestGui(plugin, questService);
        var player = server().addPlayer();
        questGui.openBoard(player, astPlayer, board, "npc-armor");

        var item = player.getOpenInventory().getTopInventory().getItem(10);
        assertEquals(Material.TURTLE_HELMET, item.getType());
        assertTrue(item.getItemMeta().getItemFlags().containsAll(Set.of(ItemFlag.values())));
        assertEquals(
            "状態: 受領可能",
            PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().lore().getFirst())
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: クールタイムの残り時間は60秒以上でも秒数だけでなく、分・時間を含む日本語表示へ変換する。
     */
    @Test
    void formatsCooldownAsReadableJapaneseDuration() {
        assertEquals("0秒", QuestGui.formatDuration(0));
        assertEquals("59秒", QuestGui.formatDuration(59));
        assertEquals("1分", QuestGui.formatDuration(60));
        assertEquals("1分10秒", QuestGui.formatDuration(70));
        assertEquals("1時間1分10秒", QuestGui.formatDuration(3_670));
        assertEquals("1日1時間1分10秒", QuestGui.formatDuration(90_070));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: クールタイム中のボード項目は、状態・再受領条件・残り時間を人間が読める単位で表示する。
     */
    @Test
    void rendersCooldownWithRemainingDurationAndRepeatRule() {
        PluginMock plugin = PluginMock.builder().withPluginName("astralrecord").build();
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        QuestDefinition quest = new QuestDefinition(
            "quest-cooldown",
            "待機クエスト",
            List.of(),
            Material.CLOCK,
            QuestRepeatMode.COOLDOWN,
            3_600L,
            QuestCompletionMode.NPC,
            "village_elder",
            List.of(new QuestObjectiveDefinition(
                "defeat-wolf",
                QuestObjectiveType.KILL_MOB,
                "wolf",
                "オオカミ",
                1
            )),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestBoardDefinition board = new QuestBoardDefinition(
            "board-cooldown",
            "待機ボード",
            List.of(new QuestBoardEntry("quest-cooldown", 1, 0, null, null))
        );
        when(questService.findQuest(quest.id())).thenReturn(quest);
        when(questService.displayState(astPlayer, quest)).thenReturn(QuestDisplayState.COOLDOWN);
        when(questService.cooldownRemainingSeconds(astPlayer, quest)).thenReturn(70L);

        QuestGui questGui = new QuestGui(plugin, questService);
        var player = server().addPlayer();
        questGui.openBoard(player, astPlayer, board, "village_elder");

        List<String> lore = player.getOpenInventory().getTopInventory().getItem(10).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
        assertTrue(lore.contains("状態: 再受領待ち"));
        assertTrue(lore.contains("再受領: 完了後に1時間待機"));
        assertTrue(lore.contains("再受領まで: 1分10秒"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 各目標の進行数は、達成済みなら緑、未達成なら黄色で表示し、文言でも達成状態を示す。
     */
    @Test
    void colorsObjectiveProgressByCompletionState() {
        PluginMock plugin = PluginMock.builder().withPluginName("astralrecord").build();
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        QuestDefinition quest = new QuestDefinition(
            "quest-progress",
            "進行クエスト",
            List.of(),
            Material.PAPER,
            QuestRepeatMode.ONCE,
            0L,
            QuestCompletionMode.NPC,
            null,
            List.of(
                new QuestObjectiveDefinition("defeat-wolf", QuestObjectiveType.KILL_MOB, "wolf", "オオカミ", 1),
                new QuestObjectiveDefinition("gather-herb", QuestObjectiveType.GATHERING, "herb", "薬草", 3)
            ),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestBoardDefinition board = new QuestBoardDefinition(
            "board-progress",
            "進行ボード",
            List.of(new QuestBoardEntry("quest-progress", 1, 0, null, null))
        );
        QuestProgress progress = new QuestProgress(
            quest.id(),
            0L,
            null,
            Map.of("defeat-wolf", 1, "gather-herb", 1),
            false
        );
        when(questService.findQuest(quest.id())).thenReturn(quest);
        when(questService.displayState(astPlayer, quest)).thenReturn(QuestDisplayState.IN_PROGRESS);
        when(questService.progress(astPlayer, quest.id())).thenReturn(progress);

        QuestGui questGui = new QuestGui(plugin, questService);
        var player = server().addPlayer();
        questGui.openBoard(player, astPlayer, board, null);

        List<Component> lore = player.getOpenInventory().getTopInventory().getItem(10).getItemMeta().lore();
        Component completed = lore.stream()
            .filter(line -> PlainTextComponentSerializer.plainText().serialize(line).contains("オオカミ"))
            .findFirst()
            .orElseThrow();
        Component incomplete = lore.stream()
            .filter(line -> PlainTextComponentSerializer.plainText().serialize(line).contains("薬草"))
            .findFirst()
            .orElseThrow();
        assertEquals("- 討伐: オオカミ  1 / 1  達成", PlainTextComponentSerializer.plainText().serialize(completed));
        assertEquals("- 採取: 薬草  1 / 3  未達成", PlainTextComponentSerializer.plainText().serialize(incomplete));
        assertEquals(NamedTextColor.GREEN, completed.children().getFirst().color());
        assertEquals(NamedTextColor.YELLOW, incomplete.children().getFirst().color());
    }
}
