package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryAuthoritativeCurrencySyncTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 12.1 外部取引後の通貨正本再同期
     * 検証契約: 別アカウント取引で更新された通貨はローカル加算せず、API正本snapshotで置換する。
     */
    @Test
    void refreshAuthoritativeCurrencyEntriesReplacesSellerCurrencyWithoutDirtyingState() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryModel currency = new InventoryModel(
            inventoryId,
            accountId,
            InventoryType.CURRENCY,
            InventoryProfile.GAME.getCode(),
            9,
            true,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel stale = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            100L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel authoritative = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            125L,
            null,
            baselineAt,
            baselineAt.plusSeconds(1),
            accountId,
            accountId,
            false
        );
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.putInventory(currency);
        state.replaceEntriesFromLoad(inventoryId, List.of(stale));
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        when(inventoryRepository.findEntries(inventoryId)).thenReturn(List.of(authoritative));
        InventoryService service = new InventoryService(
            inventoryRepository,
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );

        boolean refreshed = service.refreshAuthoritativeCurrencyEntries(accountId);

        assertTrue(refreshed);
        assertEquals(List.of(authoritative), state.snapshotEntries(inventoryId));
        assertFalse(state.isDirty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 12.1 外部取引後の通貨正本再同期
     * 検証契約: 正本取得中に通貨entryが変わった場合、再同期は未保存のローカル変更を上書きしない。
     */
    @Test
    void refreshAuthoritativeCurrencyEntriesDoesNotOverwriteConcurrentLocalCurrencyChange() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryModel currency = new InventoryModel(
            inventoryId,
            accountId,
            InventoryType.CURRENCY,
            InventoryProfile.GAME.getCode(),
            9,
            true,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel stale = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            100L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel localChange = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            110L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel authoritative = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            125L,
            null,
            baselineAt,
            baselineAt.plusSeconds(1),
            accountId,
            accountId,
            false
        );
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.putInventory(currency);
        state.replaceEntriesFromLoad(inventoryId, List.of(stale));
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        when(inventoryRepository.findEntries(inventoryId)).thenAnswer(ignored -> {
            state.replaceEntries(inventoryId, List.of(localChange));
            return List.of(authoritative);
        });
        InventoryService service = new InventoryService(
            inventoryRepository,
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );

        boolean refreshed = service.refreshAuthoritativeCurrencyEntries(accountId);

        assertFalse(refreshed);
        assertEquals(List.of(localChange), state.snapshotEntries(inventoryId));
        assertTrue(state.isDirty());
    }
}
