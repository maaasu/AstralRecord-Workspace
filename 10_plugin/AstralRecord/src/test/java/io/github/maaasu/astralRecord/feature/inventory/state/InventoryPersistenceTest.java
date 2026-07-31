package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryPersistenceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### dirty耐久値照会・保存・破棄
     * 検証契約: dirty耐久値をflushしてもpendingが残る場合、inventoryのsaveNowはfalseを返す。
     */
    @Test
    void saveNowFailsWhileEquipmentDurabilityRemainsPending() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        when(itemService.hasDirtyEquipmentDurability(accountId)).thenReturn(true, true);
        when(itemService.flushDirtyEquipmentDurability(accountId)).thenReturn(true);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService
        );

        boolean succeeded = persistence.saveNow(state);

        assertFalse(succeeded);
        verify(itemService).flushDirtyEquipmentDurability(accountId);
    }
}
