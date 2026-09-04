package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestItemStackDefinition;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestServiceItemDisplayTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 13. NPC interaction・GUI
     * 検証契約: item masterの表示名に含まれる&カラーコードを報酬表示Componentへ変換し、raw &を残さない。
     */
    @Test
    void convertsMasterItemColorCodeForRewardDisplayComponent() {
        ItemService itemService = mock(ItemService.class);
        ItemModel item = DesignTestFixtures.item(
            "freya_orb",
            "&dフレイヤのオーブ",
            ItemCategory.ORB,
            64
        );
        when(itemService.findLoadedById(item.getId())).thenReturn(item);

        QuestService questService = new QuestService(
            null,
            mock(QuestDefinitionRepository.class),
            mock(QuestBoardRepository.class),
            mock(QuestPlayerStateRepository.class),
            itemService,
            mock(InventoryService.class),
            mock(AccountService.class),
            mock(PlayerClassService.class),
            mock(StatusService.class),
            mock(ParticleDisplayService.class)
        );

        Component display = questService.resolveItemDisplayComponent(
            new QuestItemStackDefinition("freya_orb", ItemCategory.ORB.getApiValue(), 5)
        );
        String legacyDisplay = LegacyComponentSerializer.legacySection().serialize(display);

        assertTrue(legacyDisplay.contains("§dフレイヤのオーブ"));
        assertFalse(legacyDisplay.contains("&d"));
    }
}
