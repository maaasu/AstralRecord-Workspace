package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonRewardBulkInventoryTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 一括付与の途中でBAG容量が尽きる場合は操作前の空inventoryへ戻し、部分付与を残さない。
     */
    @Test
    void atomicGrantRestoresInventoryWhenLaterRewardCannotFit() {
        ItemService itemService = mock(ItemService.class);
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        InventoryHarness harness = inventoryHarness(itemService, astPlayer, 1);
        UUID accountId = astPlayer.getAccount().getUuid();
        ItemModel first = DesignTestFixtures.item("first_reward", ItemCategory.EQUIPMENT, 1);
        ItemModel second = DesignTestFixtures.item("second_reward", ItemCategory.MATERIAL, 64);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        UUID instanceId = UUID.randomUUID();
        Runnable equipmentRollback = mock(Runnable.class);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(equipmentRollback);
        when(itemService.createLocalEquipmentInstance(eq(first), eq(accountId))).thenReturn(instance);

        assertNull(harness.inventoryService().addRewardsToNormalInventoryAtomically(
                astPlayer,
                List.of(
                        new InventoryService.NormalInventoryReward(first, 1),
                        new InventoryService.NormalInventoryReward(second, 1)),
                "dungeon_clear"));
        assertTrue(harness.state().snapshotEntries(harness.bag().getInventoryId()).isEmpty());
        verify(equipmentRollback).run();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 装備個体生成中はplayer state lockを保持し、保存用snapshotは個体entry追加後まで取得できない。
     */
    @Test
    void inventorySnapshotWaitsUntilEquipmentEntryIsAdded() throws Exception {
        ItemService itemService = mock(ItemService.class);
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        InventoryHarness harness = inventoryHarness(itemService, astPlayer, 1);
        UUID accountId = astPlayer.getAccount().getUuid();
        ItemModel equipment = DesignTestFixtures.item("equipment_reward", ItemCategory.EQUIPMENT, 1);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        UUID instanceId = UUID.randomUUID();
        CountDownLatch instanceCreationEntered = new CountDownLatch(1);
        CountDownLatch releaseInstanceCreation = new CountDownLatch(1);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(() -> { });
        when(itemService.createLocalEquipmentInstance(eq(equipment), eq(accountId))).thenAnswer(ignored -> {
            instanceCreationEntered.countDown();
            if (!releaseInstanceCreation.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish equipment creation");
            }
            return instance;
        });

        CompletableFuture<InventoryService.InventoryGrantReceipt> grant = CompletableFuture.supplyAsync(() ->
                harness.inventoryService().addRewardsToNormalInventoryAtomically(
                        astPlayer,
                        List.of(new InventoryService.NormalInventoryReward(equipment, 1)),
                        "dungeon_clear"));
        assertTrue(instanceCreationEntered.await(5, TimeUnit.SECONDS));
        CountDownLatch snapshotAttempted = new CountDownLatch(1);
        CompletableFuture<InventoryService.InventoryStateSnapshot> snapshot = CompletableFuture.supplyAsync(() -> {
            snapshotAttempted.countDown();
            return harness.inventoryService().snapshotState(accountId);
        });
        assertTrue(snapshotAttempted.await(5, TimeUnit.SECONDS));
        try {
            assertFalse(snapshot.isDone());
        } finally {
            releaseInstanceCreation.countDown();
        }

        assertNotNull(grant.get(5, TimeUnit.SECONDS));
        InventoryService.InventoryStateSnapshot captured = snapshot.get(5, TimeUnit.SECONDS);
        assertNotNull(captured);
        assertEquals(instanceId, captured.entriesByInventoryId()
                .get(harness.bag().getInventoryId()).getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 未作成inventoryを含む複数報酬が容量不足となった場合は、親inventory、entry、dirty scope、表示種別を操作前へ戻す。
     */
    @Test
    void failedGrantRemovesNewParentInventoryAndRestoresExistingState() {
        ItemService itemService = mock(ItemService.class);
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        InventoryHarness harness = inventoryHarness(itemService, astPlayer, 1);
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryEntryModel existing = inventoryEntry(
                harness.bag().getInventoryId(), accountId, "existing_item");
        harness.state().replaceEntriesFromLoad(harness.bag().getInventoryId(), List.of(existing));
        harness.state().setDisplayedType(InventoryType.HOTBAR);
        PlayerInventoryState.InventoryMutationSnapshot before =
                harness.state().snapshotInventoryMutationState();
        Runnable equipmentRollback = mock(Runnable.class);
        when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(equipmentRollback);
        ItemModel currency = DesignTestFixtures.item("currency_reward", ItemCategory.CURRENCY, 64);
        ItemModel material = DesignTestFixtures.item("material_reward", ItemCategory.MATERIAL, 64);

        assertNull(harness.inventoryService().addRewardsToNormalInventoryAtomically(
                astPlayer,
                List.of(
                        new InventoryService.NormalInventoryReward(currency, 1),
                        new InventoryService.NormalInventoryReward(material, 1)),
                "dungeon_clear"));

        assertEquals(before, harness.state().snapshotInventoryMutationState());
        verify(equipmentRollback).run();
    }

    /** テスト用のロード済みplayer inventoryを構成します。 */
    private InventoryHarness inventoryHarness(
            ItemService itemService,
            AstPlayer astPlayer,
            int bagCapacity
    ) {
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(
                inventoryRepository,
                loadoutRepository,
                itemService,
                mock(io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository.class));
        InventoryService inventoryService = new InventoryService(
                inventoryRepository,
                loadoutRepository,
                itemService,
                new ItemStackFactory(mock(LootService.class), itemService),
                stateRegistry,
                persistence,
                new InventorySaveCoordinator(persistence, stateRegistry, Runnable::run));
        PlayerInventoryState state = new PlayerInventoryState(astPlayer.getAccount().getUuid());
        state.setBagSlotCapacity(bagCapacity);
        InventoryModel bag = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.BAG);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        stateRegistry.put(state);
        return new InventoryHarness(inventoryService, state, bag);
    }

    /** 指定inventoryへ配置済みの通常item entryを作成します。 */
    private InventoryEntryModel inventoryEntry(UUID inventoryId, UUID accountId, String itemId) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
                UUID.randomUUID(),
                inventoryId,
                1,
                ItemCategory.MATERIAL.getApiValue(),
                itemId,
                null,
                null,
                1L,
                null,
                now,
                now,
                accountId,
                accountId,
                false);
    }

    private record InventoryHarness(
            InventoryService inventoryService,
            PlayerInventoryState state,
            InventoryModel bag
    ) {
    }
}
