package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResultType;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemServiceRepairDurabilityRaceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### dirty耐久値照会・保存・破棄
     * 検証契約: オーブ操作前に既存damageをflush済みなら、FULL修理と固定値修理のAPI確定耐久値を古いdirty差分で減らさずそのままcacheする。
     */
    @Test
    void preFlushedDamageCannotUndoFullOrFixedRepair() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String accountId = UUID.randomUUID().toString();
        String fullInstanceId = UUID.randomUUID().toString();
        String fixedInstanceId = UUID.randomUUID().toString();
        when(repository.createEquipmentInstance(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                instance(fullInstanceId, accountId, 100, 100),
                instance(fixedInstanceId, accountId, 100, 100)
            );
        when(repository.updateEquipmentDurability(anyString(), anyInt(), anyString()))
            .thenAnswer(invocation -> instance(
                invocation.getArgument(0, String.class),
                accountId,
                100,
                invocation.getArgument(1, Integer.class)
            ));
        assertNotNull(service.createEquipmentInstance("full", accountId, "test", accountId));
        assertNotNull(service.createEquipmentInstance("fixed", accountId, "test", accountId));
        assertNotNull(service.updateEquipmentDurability(fullInstanceId, 40, accountId));
        assertNotNull(service.updateEquipmentDurability(fixedInstanceId, 40, accountId));
        assertTrue(service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));

        String fullOperationId = UUID.randomUUID().toString();
        String fixedOperationId = UUID.randomUUID().toString();
        when(repository.applyEquipmentOrbOperation(
            fullOperationId, accountId, fullInstanceId, "full-orb-entry", "orb.repair_full"
        )).thenReturn(repairResult(
            fullOperationId,
            instance(fullInstanceId, accountId, 100, 100),
            60
        ));
        when(repository.applyEquipmentOrbOperation(
            fixedOperationId, accountId, fixedInstanceId, "fixed-orb-entry", "orb.repair_fixed"
        )).thenReturn(repairResult(
            fixedOperationId,
            instance(fixedInstanceId, accountId, 100, 70),
            30
        ));

        EquipmentOrbOperationResult full = service.applyEquipmentOrbOperation(
            fullOperationId, accountId, fullInstanceId, "full-orb-entry", "orb.repair_full");
        EquipmentOrbOperationResult fixed = service.applyEquipmentOrbOperation(
            fixedOperationId, accountId, fixedInstanceId, "fixed-orb-entry", "orb.repair_fixed");

        assertNotNull(full);
        assertNotNull(fixed);
        assertEquals(100, full.getEquipment().getDurabilityValue());
        assertEquals(70, fixed.getEquipment().getDurabilityValue());
        assertFalse(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 耐久値キャッシュ更新
     * 検証契約: FULL修理APIの待機中に耐久40から30への新規damageだけが発生した場合、修理結果100へ差分10だけを反映して90をdirtyとして残す。
     */
    @Test
    void damageReceivedWhileRepairApiWaitsIsMergedAsOnlyTheNewDelta() throws Exception {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String accountId = UUID.randomUUID().toString();
        String instanceId = UUID.randomUUID().toString();
        when(repository.createEquipmentInstance(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(instance(instanceId, accountId, 100, 100));
        when(repository.updateEquipmentDurability(anyString(), anyInt(), anyString()))
            .thenAnswer(invocation -> instance(
                instanceId,
                accountId,
                100,
                invocation.getArgument(1, Integer.class)
            ));
        assertNotNull(service.createEquipmentInstance("repair", accountId, "test", accountId));
        assertNotNull(service.updateEquipmentDurability(instanceId, 40, accountId));
        assertTrue(service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));

        CountDownLatch apiStarted = new CountDownLatch(1);
        CountDownLatch releaseApi = new CountDownLatch(1);
        String operationId = UUID.randomUUID().toString();
        when(repository.applyEquipmentOrbOperation(
            operationId, accountId, instanceId, "orb-entry", "orb.repair_full"
        )).thenAnswer(invocation -> {
            apiStarted.countDown();
            assertTrue(releaseApi.await(2, TimeUnit.SECONDS));
            return repairResult(operationId, instance(instanceId, accountId, 100, 100), 60);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<EquipmentOrbOperationResult> repair = executor.submit(() ->
                service.applyEquipmentOrbOperation(
                    operationId, accountId, instanceId, "orb-entry", "orb.repair_full"));
            assertTrue(apiStarted.await(2, TimeUnit.SECONDS));

            assertNotNull(service.updateEquipmentDurability(instanceId, 30, accountId));
            releaseApi.countDown();

            EquipmentOrbOperationResult result = repair.get(2, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(90, result.getEquipment().getDurabilityValue());
            assertEquals(90, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertTrue(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));

            assertTrue(service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));
            assertEquals(90, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertFalse(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));
        } finally {
            releaseApi.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### dirty耐久値照会・保存・破棄
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 進行中のdirty耐久flushを事前保存として待ち、完了前は修理APIを開始せず、完了後のFULL修理結果を旧flush応答で上書きしない。
     */
    @Test
    void accountLaneWaitsForDurabilityFlushBeforeStartingRepair() throws Exception {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        UUID accountId = UUID.randomUUID();
        String accountIdText = accountId.toString();
        String instanceId = UUID.randomUUID().toString();
        when(repository.createEquipmentInstance(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(instance(instanceId, accountIdText, 100, 100));
        assertNotNull(service.createEquipmentInstance("repair", accountIdText, "test", accountIdText));
        assertNotNull(service.updateEquipmentDurability(instanceId, 40, accountIdText));

        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(repository.updateEquipmentDurability(instanceId, 40, accountIdText))
            .thenAnswer(invocation -> {
                flushStarted.countDown();
                assertTrue(releaseFlush.await(2, TimeUnit.SECONDS));
                return instance(instanceId, accountIdText, 100, 40);
            });
        String operationId = UUID.randomUUID().toString();
        AtomicBoolean repairApiCalled = new AtomicBoolean();
        when(repository.applyEquipmentOrbOperation(
            operationId, accountIdText, instanceId, "orb-entry", "orb.repair_full"
        )).thenAnswer(invocation -> {
            repairApiCalled.set(true);
            return repairResult(operationId, instance(instanceId, accountIdText, 100, 100), 60);
        });

        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenAnswer(invocation ->
            service.flushDirtyEquipmentDurability(accountId));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try {
            var repair = coordinator.executeExclusiveAfterSave(accountId, () ->
                service.applyEquipmentOrbOperation(
                    operationId, accountIdText, instanceId, "orb-entry", "orb.repair_full"));
            assertTrue(flushStarted.await(2, TimeUnit.SECONDS));
            assertFalse(repairApiCalled.get());

            releaseFlush.countDown();

            EquipmentOrbOperationResult result = repair.get(2, TimeUnit.SECONDS);
            assertTrue(repairApiCalled.get());
            assertNotNull(result);
            assertEquals(100, result.getEquipment().getDurabilityValue());
            assertEquals(100, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertFalse(service.hasDirtyEquipmentDurability(accountId));
        } finally {
            releaseFlush.countDown();
            executor.shutdownNow();
        }
    }

    private static EquipmentOrbOperationResult repairResult(
        String operationId,
        EquipmentInstance equipment,
        int repairedAmount
    ) {
        return new EquipmentOrbOperationResult(
            operationId,
            EquipmentOrbOperationResultType.APPLIED,
            "REPAIR",
            equipment,
            true,
            List.of("orb-entry"),
            true,
            false,
            null,
            null,
            repairedAmount,
            null
        );
    }

    private static EquipmentInstance instance(
        String instanceId,
        String accountId,
        int durabilityMax,
        int durabilityValue
    ) {
        return new EquipmentInstance(
            instanceId,
            accountId,
            "debug_sword",
            0,
            0,
            0,
            durabilityMax,
            durabilityValue,
            "2026-08-10T00:00:00",
            "2026-08-10T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
