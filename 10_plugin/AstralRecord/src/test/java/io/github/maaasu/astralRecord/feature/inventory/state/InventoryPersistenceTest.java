package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: API正本だけが更新した古いentry snapshotは、409後に正本へ差し替えて保存laneを継続する。
     */
    @Test
    void staleSnapshotConflictRefreshesUnchangedEntriesFromApi() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryModel inventory = new InventoryModel(
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
        state.putInventory(inventory);
        state.replaceEntriesFromLoad(inventoryId, List.of(stale));
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        when(inventoryRepository.replaceEntries(eq(inventoryId), anyList(), eq(accountId)))
            .thenThrow(new InventoryApiException("PUT", "/api/inventory/entries", 409, "stale"));
        when(inventoryRepository.findEntries(inventoryId)).thenReturn(List.of(authoritative));
        when(itemService.hasDirtyEquipmentDurability(accountId)).thenReturn(false);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService
        );

        InventoryPersistence.PersistedInventoryBaseline baseline = persistence.saveNowWithBaseline(state);

        assertNotNull(baseline);
        assertEquals(List.of(authoritative), state.snapshotEntries(inventoryId));
        assertEquals(List.of(authoritative), baseline.entries(inventoryId));
        assertFalse(persistence.hasPendingChanges(state));
        verify(inventoryRepository).findEntries(inventoryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: 409の再取得中に同じentryへローカル変更が入った場合は、その変更を正本snapshotで上書きしない。
     */
    @Test
    void staleSnapshotConflictDoesNotOverwriteConcurrentLocalEntryChange() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryEntryModel submitted = new InventoryEntryModel(
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
        state.replaceEntriesFromLoad(inventoryId, List.of(submitted));
        state.replaceEntries(inventoryId, List.of(localChange));

        boolean replaced = state.replaceEntriesFromAuthoritativeSnapshotIfUnchanged(
            inventoryId,
            List.of(submitted),
            List.of(authoritative)
        );

        assertFalse(replaced);
        assertEquals(List.of(localChange), state.snapshotEntries(inventoryId));
    }
}
