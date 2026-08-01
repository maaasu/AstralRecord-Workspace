package io.github.maaasu.astralRecord.feature.quest.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: 一覧では受領できない論理スロットだけを受領枠案内で埋め、slot 49には受領枠専用itemを置かない。
     */
    @Test
    void rendersQuestLimitGuidesOnlyInUnavailableListSlotsInsteadOfSlot49() {
        AstralRecord plugin = mock(AstralRecord.class);
        QuestService questService = mock(QuestService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(plugin.getName()).thenReturn("astralrecord");
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
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, inventory.getItem(49).getType());
    }
}
