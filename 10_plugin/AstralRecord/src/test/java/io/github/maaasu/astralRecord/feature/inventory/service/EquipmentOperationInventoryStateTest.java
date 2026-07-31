package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentOperationInventoryStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 12. プレイヤー状態モデル
     * 検証契約: BAG実効容量の初期値を24にする。
     */
    @Test
    void bagRuntimeCapacityDefaultsToTwentyFour() {
        assertEquals(24, new PlayerInventoryState(UUID.randomUUID()).getBagSlotCapacity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: BAG容量を最小0・上限なしとし、容量変更でscroll位置を失わない。
     */
    @Test
    void bagRuntimeCapacityHasZeroFloorNoMaximumAndKeepsOverflowScroll() {
        PlayerInventoryState state = new PlayerInventoryState(UUID.randomUUID());
        state.setBagScrollRow(3);

        state.setBagSlotCapacity(-1);
        assertEquals(0, state.getBagSlotCapacity());
        assertEquals(3, state.getBagScrollRow());

        state.setBagSlotCapacity(1_000);
        assertEquals(1_000, state.getBagSlotCapacity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: 一時保持装備を元inventoryの元slotへinstance ID付きで戻す。
     */
    @Test
    void restoresHeldEntryToItsOriginalStateAndSlot() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 32);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        InventoryEntryModel held = equipmentEntry(accountId, bag.getInventoryId(), 4, UUID.randomUUID());

        assertTrue(EquipmentOperationInventoryState.restoreEntry(state, held));

        List<InventoryEntryModel> restored = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, restored.size());
        assertEquals(held.getInventoryEntryId(), restored.getFirst().getInventoryEntryId());
        assertEquals(4, restored.getFirst().getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: 元slotが占有済みなら既存entryを上書きせず空slotへ再配置する。
     */
    @Test
    void relocatesHeldEntryWithoutOverwritingAnOccupiedOriginalSlot() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 32);
        state.putInventory(bag);
        InventoryEntryModel occupant = equipmentEntry(accountId, bag.getInventoryId(), 1, UUID.randomUUID());
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(occupant));
        InventoryEntryModel held = equipmentEntry(accountId, bag.getInventoryId(), 1, UUID.randomUUID());

        assertTrue(EquipmentOperationInventoryState.restoreEntry(state, held));

        List<InventoryEntryModel> restored = state.snapshotEntries(bag.getInventoryId());
        assertEquals(2, restored.size());
        assertEquals(2, restored.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(held.getInventoryEntryId()))
            .findFirst()
            .orElseThrow()
            .getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: hotbar由来の一時保持装備をhotbarではなくBAGへ返却する。
     */
    @Test
    void restoresHeldEntryFromHotbarToBagInsteadOfHotbar() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 32);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR, 9);
        state.putInventory(bag);
        state.putInventory(hotbar);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of());
        InventoryEntryModel held = equipmentEntry(accountId, hotbar.getInventoryId(), 1, UUID.randomUUID());

        assertTrue(EquipmentOperationInventoryState.restoreEntry(state, held));
        assertEquals(1, state.snapshotEntries(bag.getInventoryId()).size());
        assertEquals(1, state.snapshotEntries(bag.getInventoryId()).getFirst().getSlotIndex());
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: BAG復元可否を永続slotCapacityでなくINVENTORY_SLOTS実効容量だけで判定する。
     */
    @Test
    void bagRestoreIgnoresPersistedCapacityAndUsesStatusCapacity() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.setBagSlotCapacity(2);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 32);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            equipmentEntry(accountId, bag.getInventoryId(), 1, UUID.randomUUID()),
            equipmentEntry(accountId, bag.getInventoryId(), 2, UUID.randomUUID())
        ));
        InventoryEntryModel held = equipmentEntry(accountId, bag.getInventoryId(), 3, UUID.randomUUID());

        assertFalse(EquipmentOperationInventoryState.restoreEntry(state, held));
        assertEquals(2, state.snapshotEntries(bag.getInventoryId()).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: 支払前snapshot復元後も保持装備を復元またはinstance ID指定で除去できる。
     */
    @Test
    void restoresPaymentSnapshotThenCanRestoreOrRemoveHeldEquipment() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 32);
        state.putInventory(bag);
        InventoryEntryModel material = stackEntry(accountId, bag.getInventoryId(), 1, "enhance_stone", 3L);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(material));
        Map<UUID, List<InventoryEntryModel>> snapshotEntries = new LinkedHashMap<>();
        snapshotEntries.put(bag.getInventoryId(), List.of(material));
        InventoryService.InventoryStateSnapshot snapshot = new InventoryService.InventoryStateSnapshot(
            accountId,
            snapshotEntries,
            InventoryType.BAG,
            true
        );
        InventoryEntryModel held = equipmentEntry(accountId, bag.getInventoryId(), 2, UUID.randomUUID());
        state.replaceEntries(bag.getInventoryId(), List.of());

        assertTrue(EquipmentOperationInventoryState.restoreSnapshot(state, snapshot));
        assertTrue(EquipmentOperationInventoryState.restoreEntry(state, held));
        assertEquals(2, state.snapshotEntries(bag.getInventoryId()).size());

        assertTrue(EquipmentOperationInventoryState.removeEntry(state, held));
        List<InventoryEntryModel> remaining = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, remaining.size());
        assertFalse(remaining.stream().anyMatch(entry -> held.getInstanceId().equals(entry.getInstanceId())));
    }

    private static InventoryEntryModel equipmentEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        UUID instanceId
    ) {
        return entry(accountId, inventoryId, slot, "EQUIPMENT", null, "EQUIPMENT", instanceId, 1L);
    }

    private static InventoryEntryModel stackEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        String itemId,
        long quantity
    ) {
        return entry(accountId, inventoryId, slot, "MATERIAL", itemId, null, null, quantity);
    }

    private static InventoryEntryModel entry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        String category,
        String itemId,
        String instanceType,
        UUID instanceId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slot,
            category,
            itemId,
            instanceType,
            instanceId,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }
}
