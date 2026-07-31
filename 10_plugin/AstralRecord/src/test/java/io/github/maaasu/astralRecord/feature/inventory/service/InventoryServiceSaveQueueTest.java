package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryServiceSaveQueueTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 12. 即時保存
     * 検証契約: 即時保存要求をInventorySaveCoordinatorへ委譲し、そのfutureを返してInventoryPersistenceを直接呼ばない。
     */
    @Test
    void delegatesImmediateSaveToAccountQueueWithoutCallingPersistenceDirectly() {
        UUID accountId = UUID.randomUUID();
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventorySaveCoordinator saveCoordinator = mock(InventorySaveCoordinator.class);
        CompletableFuture<Boolean> expected = CompletableFuture.completedFuture(true);
        when(saveCoordinator.saveNow(accountId)).thenReturn(expected);
        InventoryService service = new InventoryService(
            inventoryRepository,
            loadoutRepository,
            itemService,
            mock(ItemStackFactory.class),
            new PlayerInventoryStateRegistry(),
            persistence,
            saveCoordinator
        );

        CompletableFuture<Boolean> actual = service.saveNow(accountId);

        assertSame(expected, actual);
        verify(saveCoordinator).saveNow(accountId);
        verifyNoInteractions(persistence);
    }
}
