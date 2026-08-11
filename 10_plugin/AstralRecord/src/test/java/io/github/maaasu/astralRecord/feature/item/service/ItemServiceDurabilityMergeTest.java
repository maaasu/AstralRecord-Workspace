package io.github.maaasu.astralRecord.feature.item.service;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemServiceDurabilityMergeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 初回POSTがNOT_ELIGIBLEでも対象が引き続き本人所有なら、APIが返した現行装備をcacheへ反映し、条件変化後の値で一覧再構築できるようにする。
     */
    @Test
    void notEligibleOwnedTargetMergesCurrentEquipmentIntoCache() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        prime(service, repository, instanceId, accountId);
        String operationId = UUID.randomUUID().toString();
        EquipmentInstance current = instance(instanceId, accountId, 2, 0, 100, 85);
        EquipmentOrbOperationResult notEligible = new EquipmentOrbOperationResult(
            operationId,
            EquipmentOrbOperationResultType.NOT_ELIGIBLE,
            "ENHANCE",
            current,
            true,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null
        );
        when(repository.applyEquipmentOrbOperation(
            operationId,
            accountId,
            instanceId,
            "orb-entry",
            "orb.weapon_tyr"
        )).thenReturn(notEligible);

        EquipmentOrbOperationResult result = service.applyEquipmentOrbOperation(
            operationId,
            accountId,
            instanceId,
            "orb-entry",
            "orb.weapon_tyr"
        );

        assertNotNull(result);
        assertEquals(EquipmentOrbOperationResultType.NOT_ELIGIBLE, result.getResult());
        assertTrue(result.getTargetAvailable());
        assertFalse(result.getPaymentConsumed());
        assertEquals(2, result.getEquipment().getEnhanceLevel());
        assertEquals(85, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 耐久値キャッシュ更新
     * 検証契約: 未保存の耐久減少がある装備へ統一オーブ操作結果を反映すると、更新後最大耐久値へ損失耐久量を移し替えてdirtyを保持する。
     */
    @Test
    void unifiedOrbMutationPreservesPendingDurabilityDamage() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        EquipmentInstance base = instance(instanceId, accountId, 0, 0, 100, 100);
        when(repository.createEquipmentInstance("debug_sword", accountId, "test", accountId))
            .thenReturn(base);

        assertNotNull(service.createEquipmentInstance("debug_sword", accountId, "test", accountId));
        assertNotNull(service.updateEquipmentDurability(instanceId, 40, accountId));

        String operationId = UUID.randomUUID().toString();
        EquipmentInstance apiResult = instance(instanceId, accountId, 1, 0, 120, 120);
        when(repository.applyEquipmentOrbOperation(
            operationId,
            accountId,
            instanceId,
            "orb-entry",
            "orb.weapon_tyr"
        )).thenReturn(applied(operationId, apiResult));

        EquipmentOrbOperationResult result = service.applyEquipmentOrbOperation(
            operationId,
            accountId,
            instanceId,
            "orb-entry",
            "orb.weapon_tyr"
        );

        assertNotNull(result);
        assertNotNull(result.getEquipment());
        assertEquals(60, result.getEquipment().getDurabilityValue());
        assertEquals(60, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
        assertTrue(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### dirty耐久値照会・保存・破棄
     * 検証契約: flush通信中に同じ装備へ新しい耐久減少が入った場合、旧revisionの応答は新しいdamageとdirtyを上書きせず次回flushへ残す。
     */
    @Test
    void concurrentDamageDuringFlushCannotBeLostByOlderRevision() throws Exception {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        prime(service, repository, instanceId, accountId);
        assertNotNull(service.updateEquipmentDurability(instanceId, 40, accountId));

        CountDownLatch firstFlushStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFlush = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(repository.updateEquipmentDurability(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
            .thenAnswer(invocation -> {
                int call = calls.incrementAndGet();
                int durability = invocation.getArgument(1, Integer.class);
                if (call == 1) {
                    firstFlushStarted.countDown();
                    assertTrue(releaseFirstFlush.await(2, TimeUnit.SECONDS));
                }
                return instance(instanceId, accountId, 0, 0, 100, durability);
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> firstFlush = executor.submit(
                () -> service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));
            assertTrue(firstFlushStarted.await(2, TimeUnit.SECONDS));

            assertNotNull(service.updateEquipmentDurability(instanceId, 30, accountId));
            releaseFirstFlush.countDown();

            assertFalse(firstFlush.get(2, TimeUnit.SECONDS));
            assertEquals(30, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertTrue(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));

            assertTrue(service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));
            assertEquals(30, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertFalse(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));
        } finally {
            releaseFirstFlush.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### dirty耐久値照会・保存・破棄
     * 検証契約: flush通信中に同じ装備のオーブmutationが確定した場合、mutationへ移し替えたdamageを旧flush応答で失わず新revisionとして再保存する。
     */
    @Test
    void concurrentOrbMutationDuringFlushKeepsMergedDamageDirty() throws Exception {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        prime(service, repository, instanceId, accountId);
        assertNotNull(service.updateEquipmentDurability(instanceId, 40, accountId));

        CountDownLatch firstFlushStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFlush = new CountDownLatch(1);
        AtomicInteger flushCalls = new AtomicInteger();
        when(repository.updateEquipmentDurability(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
            .thenAnswer(invocation -> {
                int call = flushCalls.incrementAndGet();
                int durability = invocation.getArgument(1, Integer.class);
                if (call == 1) {
                    firstFlushStarted.countDown();
                    assertTrue(releaseFirstFlush.await(2, TimeUnit.SECONDS));
                    return instance(instanceId, accountId, 0, 0, 100, durability);
                }
                return instance(instanceId, accountId, 1, 0, 120, durability);
            });
        String operationId = UUID.randomUUID().toString();
        when(repository.applyEquipmentOrbOperation(
            operationId,
            accountId,
            instanceId,
            "orb-entry",
            "orb.weapon_tyr"
        )).thenReturn(applied(
            operationId,
            instance(instanceId, accountId, 1, 0, 120, 120)
        ));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> firstFlush = executor.submit(
                () -> service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));
            assertTrue(firstFlushStarted.await(2, TimeUnit.SECONDS));

            EquipmentOrbOperationResult mutation = service.applyEquipmentOrbOperation(
                operationId,
                accountId,
                instanceId,
                "orb-entry",
                "orb.weapon_tyr"
            );
            assertNotNull(mutation);
            assertEquals(60, mutation.getEquipment().getDurabilityValue());
            releaseFirstFlush.countDown();

            assertFalse(firstFlush.get(2, TimeUnit.SECONDS));
            assertEquals(60, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertTrue(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));

            assertTrue(service.flushDirtyEquipmentDurability(UUID.fromString(accountId)));
            assertEquals(60, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
            assertFalse(service.hasDirtyEquipmentDurability(UUID.fromString(accountId)));
        } finally {
            releaseFirstFlush.countDown();
            executor.shutdownNow();
        }
    }

    private static void prime(
        ItemService service,
        ItemRepository repository,
        String instanceId,
        String accountId
    ) {
        EquipmentInstance base = instance(instanceId, accountId, 0, 0, 100, 100);
        when(repository.createEquipmentInstance("debug_sword", accountId, "test", accountId))
            .thenReturn(base);
        assertNotNull(service.createEquipmentInstance("debug_sword", accountId, "test", accountId));
    }

    private static EquipmentOrbOperationResult applied(String operationId, EquipmentInstance equipment) {
        return new EquipmentOrbOperationResult(
            operationId,
            EquipmentOrbOperationResultType.APPLIED,
            "ENHANCE",
            equipment,
            true,
            List.of("orb-entry"),
            true,
            true,
            null,
            1.0D,
            null,
            null
        );
    }

    private static EquipmentInstance instance(
        String instanceId,
        String accountId,
        int enhanceLevel,
        int rank,
        int durabilityMax,
        int durabilityValue
    ) {
        return new EquipmentInstance(
            instanceId,
            accountId,
            "debug_sword",
            enhanceLevel,
            0,
            rank,
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
