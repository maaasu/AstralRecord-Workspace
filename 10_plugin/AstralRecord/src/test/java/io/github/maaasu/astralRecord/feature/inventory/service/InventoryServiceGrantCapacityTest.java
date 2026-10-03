package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.*;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.*;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryServiceGrantCapacityTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 容量判定と個体報酬
     * 検証契約: 通常stackの事前判定は既存stack残量と空き枠を数え、満杯BAGでも残量へ追加できる。
     */
    @Test
    void stackCapacityMatchesAdditionWithAndWithoutFreeSlots() {
        for (ItemCategory category : List.of(ItemCategory.MATERIAL, ItemCategory.RUNE)) {
            Fixture fixture = fixture(1);
            var item = DesignTestFixtures.item("stack_fixture", category, 64);
            assertTrue(fixture.service.canAddItemToNormalInventory(fixture.player, item, 2));
            assertEquals(2, fixture.service.addItemToNormalInventoryStateOnly(fixture.player, item, 2, "test"));
            assertTrue(fixture.service.canAddItemToNormalInventory(fixture.player, item, 62));
            assertFalse(fixture.service.canAddItemToNormalInventory(fixture.player, item, 63));
            assertEquals(62, fixture.service.addItemToNormalInventoryStateOnly(fixture.player, item, 62, "test"));
            assertEquals(64, fixture.state.snapshotEntries(fixture.bag.getInventoryId()).getFirst().getQuantity());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 容量判定と個体報酬
     * 検証契約: 未作成BAGのstack容量判定はmaxStackを使い、個体容量は定義のmaxStackに関係なく1個1枠で数える。
     */
    @Test
    void missingBagSimulationDistinguishesStacksAndInstances() {
        Fixture fixture = fixture(1);
        fixture.state.restoreInventoryMutationState(new PlayerInventoryState(fixture.state.getAccountId()).snapshotInventoryMutationState());
        fixture.state.setBagSlotCapacity(1);
        assertTrue(fixture.service.canAddItemToStorageIfPresentOtherwiseNormalInventory(fixture.player,
            DesignTestFixtures.item("stack_fixture", ItemCategory.RUNE, 64), 2));
        for (ItemCategory category : List.of(ItemCategory.EQUIPMENT, ItemCategory.PET, ItemCategory.PET_EGG)) {
            assertFalse(fixture.service.canAddItemToStorageIfPresentOtherwiseNormalInventory(fixture.player,
                DesignTestFixtures.item("instance_fixture", category, 64), 2));
        }
        assertTrue(fixture.state.snapshotInventories().isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 容量判定と個体報酬
     * 検証契約: 準備済み個体報酬は個体IDと種別を数量1で保持し、2個目は空き枠不足なら追加しない。
     */
    @Test
    void preparedInstancesKeepTheirIdentityAndConsumeOneSlotEach() {
        for (ItemCategory category : List.of(ItemCategory.EQUIPMENT, ItemCategory.PET, ItemCategory.PET_EGG)) {
            Fixture fixture = fixture(1);
            var item = DesignTestFixtures.item("instance_fixture", category, 64);
            var type = InventoryInstanceType.valueOf(category.name());
            UUID instanceId = UUID.randomUUID();
            assertFalse(fixture.service.canAddItemToNormalInventory(fixture.player, item, 2));
            var reward = new InventoryService.PreparedInventoryReward(item, 1,
                List.of(new InventoryService.PreparedInventoryInstance(type, instanceId)));
            assertNotNull(fixture.service.addPreparedRewardsToNormalInventoryStateOnly(fixture.player, List.of(reward)));
            var entry = fixture.state.snapshotEntries(fixture.bag.getInventoryId()).getFirst();
            assertEquals(instanceId, entry.getInstanceId());
            assertEquals(type.getCode(), entry.getInstanceType());
            assertEquals(1, entry.getQuantity());
            assertFalse(fixture.service.canAddItemToNormalInventory(fixture.player, item, 1));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 容量判定と個体報酬
     * 検証契約: 個体不足・種別不一致・重複IDの報酬は先行したstack付与も戻し、個体なしentryを残さない。
     */
    @Test
    void invalidPreparedInstancesRollBackEarlierStackRewards() {
        for (ItemCategory category : List.of(ItemCategory.EQUIPMENT, ItemCategory.PET, ItemCategory.PET_EGG)) {
            var item = DesignTestFixtures.item("instance_fixture", category, 64);
            var expected = InventoryInstanceType.valueOf(category.name());
            var wrong = expected == InventoryInstanceType.EQUIPMENT ? InventoryInstanceType.PET : InventoryInstanceType.EQUIPMENT;
            UUID duplicate = UUID.randomUUID();
            var instance = new InventoryService.PreparedInventoryInstance(expected, duplicate);
            for (var invalid : List.of(
                new InventoryService.PreparedInventoryReward(item, 1, List.of()),
                new InventoryService.PreparedInventoryReward(item, 1, List.of(new InventoryService.PreparedInventoryInstance(wrong, UUID.randomUUID()))),
                new InventoryService.PreparedInventoryReward(item, 2, List.of(instance, instance)))) {
                Fixture fixture = fixture(3);
                var stacked = new InventoryService.PreparedInventoryReward(
                    DesignTestFixtures.item("stack_fixture", ItemCategory.MATERIAL, 64), 1, List.of());
                assertNull(fixture.service.addPreparedRewardsToNormalInventoryStateOnly(fixture.player, List.of(stacked, invalid)));
                assertTrue(fixture.state.snapshotEntries(fixture.bag.getInventoryId()).isEmpty());
                assertFalse(fixture.state.isDirty());
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 非同期API個体生成の予約枠を他取得へ渡さず、三者マージでは今回の予約だけを消費して他の予約を保持する。
     */
    @Test
    void reservedApiAdditionDoesNotOverflowOrConsumeAnotherReservation() {
        Fixture fixture = fixture(3);
        UUID accountId = fixture.state.getAccountId();
        var egg = DesignTestFixtures.item("reserved_instance", ItemCategory.PET_EGG, 1);
        var eggReservation = fixture.service.reserveBagSlotForPreparedInstance(fixture.player, egg).reservation();
        var otherReservation = fixture.service.reserveBagSlotForPreparedInstance(fixture.player,
            DesignTestFixtures.item("other_instance", ItemCategory.EQUIPMENT, 1)).reservation();
        assertNotNull(eggReservation);
        assertNotNull(otherReservation);
        var stacked = DesignTestFixtures.item("concurrent_stack", ItemCategory.MATERIAL, 1);
        assertEquals(1, fixture.service.addItemToNormalInventoryStateOnly(fixture.player, stacked, 2, "test"));
        assertFalse(fixture.service.canAddItemToNormalInventory(fixture.player, stacked, 1));
        UUID apiEntryId = UUID.randomUUID(), instanceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        var entry = new InventoryEntryModel(apiEntryId, fixture.bag.getInventoryId(), null,
            ItemCategory.PET_EGG.getApiValue(), egg.getId(), InventoryInstanceType.PET_EGG.getCode(),
            instanceId, 1, null, now, now, accountId, accountId, false);
        var baseline = new InventoryPersistence.PersistedInventoryBaseline(accountId,
            Map.of(fixture.bag.getInventoryId(), List.of()));
        var snapshot = new InventoryOperationSnapshot(accountId, Set.of(apiEntryId), List.of(entry), null, List.of());
        fixture.service.reconcileReservedPetGrant(accountId, baseline, snapshot, eggReservation);
        var entries = fixture.state.snapshotEntries(fixture.bag.getInventoryId());
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(value -> value.getSlotIndex() >= 1 && value.getSlotIndex() <= 3));
        assertTrue(entries.stream().noneMatch(value -> value.getSlotIndex() == 2));
        assertTrue(entries.stream().anyMatch(value -> instanceId.equals(value.getInstanceId())));
        assertFalse(fixture.service.canAddItemToNormalInventory(fixture.player, stacked, 1));
        fixture.service.releasePreparedInstanceReservation(otherReservation);
        assertTrue(fixture.service.canAddItemToNormalInventory(fixture.player, stacked, 1));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 予約済みAPI個体は予約枠へ入り、既存容量外entryのID・数量・slotを維持する。
     */
    @Test
    void reservedApiAdditionPreservesExistingOverflowEntries() {
        Fixture fixture = fixture(2);
        UUID accountId = fixture.state.getAccountId();
        LocalDateTime now = LocalDateTime.now();
        var existing = new InventoryEntryModel(UUID.randomUUID(), fixture.bag.getInventoryId(), 1,
            ItemCategory.MATERIAL.getApiValue(), "existing", null, null, 1, null, now, now, accountId, accountId, false);
        var overflow = new InventoryEntryModel(UUID.randomUUID(), fixture.bag.getInventoryId(), 3,
            ItemCategory.MATERIAL.getApiValue(), "overflow", null, null, 5, null, now, now, accountId, accountId, false);
        fixture.state.replaceEntriesFromLoad(fixture.bag.getInventoryId(), List.of(existing, overflow));
        var egg = DesignTestFixtures.item("reserved_instance", ItemCategory.PET_EGG, 1);
        var reservation = fixture.service.reserveBagSlotForPreparedInstance(fixture.player, egg).reservation();
        assertNotNull(reservation);
        UUID apiEntryId = UUID.randomUUID(), instanceId = UUID.randomUUID();
        var incoming = new InventoryEntryModel(apiEntryId, fixture.bag.getInventoryId(), null,
            ItemCategory.PET_EGG.getApiValue(), egg.getId(), InventoryInstanceType.PET_EGG.getCode(),
            instanceId, 1, null, now, now, accountId, accountId, false);
        var baseline = new InventoryPersistence.PersistedInventoryBaseline(accountId,
            Map.of(fixture.bag.getInventoryId(), List.of(existing, overflow)));
        var snapshot = new InventoryOperationSnapshot(accountId, Set.of(apiEntryId), List.of(incoming), null, List.of());
        fixture.service.reconcileReservedPetGrant(accountId, baseline, snapshot, reservation);
        var entries = fixture.state.snapshotEntries(fixture.bag.getInventoryId());
        assertEquals(existing, entries.stream().filter(value -> value.getInventoryEntryId().equals(existing.getInventoryEntryId())).findFirst().orElseThrow());
        assertEquals(overflow, entries.stream().filter(value -> value.getInventoryEntryId().equals(overflow.getInventoryEntryId())).findFirst().orElseThrow());
        assertEquals(2, entries.stream().filter(value -> value.getInventoryEntryId().equals(apiEntryId)).findFirst().orElseThrow().getSlotIndex());
    }

    private static Fixture fixture(int capacity) {
        UUID accountId = UUID.randomUUID();
        var state = new PlayerInventoryState(accountId);
        state.setBagSlotCapacity(capacity);
        var bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, capacity);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        var registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        var account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        var player = mock(AstPlayer.class);
        when(player.getAccount()).thenReturn(account);
        var service = new InventoryService(mock(InventoryRepository.class), mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class), mock(ItemStackFactory.class), registry, mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class));
        return new Fixture(player, state, bag, service);
    }

    private record Fixture(AstPlayer player, PlayerInventoryState state, InventoryModel bag, InventoryService service) { }
}
