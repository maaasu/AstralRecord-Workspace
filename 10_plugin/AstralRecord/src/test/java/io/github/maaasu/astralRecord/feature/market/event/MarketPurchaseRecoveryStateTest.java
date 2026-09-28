package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.feature.market.repository.MarketRequestRejectedException;
import io.github.maaasu.astralRecord.feature.market.repository.MarketTransportException;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MarketPurchaseRecoveryStateTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 4. 購入 > ### 処理要点
     * 検証契約: 初回購入の確定拒否はAPI未成立としてprepared境界を解除できる。
     */
    @Test
    void firstRejectedPurchaseAllowsPreparedBoundaryAbandon() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, Runnable::run);
        MarketPurchaseRecoveryState recovery = new MarketPurchaseRecoveryState();

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var prepared = coordinator.prepareExternalOperationAfterSave(accountId).join();
            assertThrows(MarketRequestRejectedException.class, () -> recovery.resolve(() -> {
                throw new MarketRequestRejectedException(409, "rejected");
            }));
            assertTrue(recovery.canAbandonOnRejection());
            coordinator.abandonPreparedExternalOperation(prepared);
            assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 4. 購入 > ### 処理要点
     * 検証契約: 通信結果不明後は同一購入receiptとbaselineを保持し、再同期失敗と後保存失敗を再試行しても購入・反映を重複させない。
     */
    @Test
    void unknownPurchaseRetainsBoundaryAndReceiptThroughReconcileAndPostSaveRetries() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        var baseline = new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
        when(persistence.saveNowWithBaseline(state)).thenReturn(baseline);
        when(persistence.saveNow(state)).thenReturn(false);
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, Runnable::run, 1_000);
        MarketPurchaseRecoveryState recovery = new MarketPurchaseRecoveryState();
        MarketTransaction receipt = new MarketTransaction(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), accountId,
            "GAME", "10a00001", null, null, 1L, "gold", 10L, 10L,
            0L, 10L, List.of(UUID.randomUUID()), java.time.Instant.now());
        AtomicInteger purchaseCalls = new AtomicInteger();
        AtomicInteger reconciliations = new AtomicInteger();
        java.util.function.Supplier<MarketTransaction> purchase = () -> {
            return switch (purchaseCalls.incrementAndGet()) {
                case 1 -> throw new MarketTransportException("unknown");
                case 2 -> throw new MarketRequestRejectedException(409, "retry rejected");
                default -> receipt;
            };
        };

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var prepared = coordinator.prepareExternalOperationAfterSave(accountId).join();
            assertThrows(MarketRequestRejectedException.class, () -> recovery.resolve(purchase));
            assertFalse(recovery.canAbandonOnRejection());
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

            assertSame(receipt, recovery.resolve(purchase));
            assertEquals(3, purchaseCalls.get());
            java.util.function.Function<InventoryPersistence.PersistedInventoryBaseline, MarketTransaction> reconcile = saved -> {
                assertSame(baseline, saved);
                if (reconciliations.incrementAndGet() == 1) throw new IllegalStateException("reconcile failed");
                return recovery.resolve(purchase);
            };
            var first = coordinator.completePreparedExternalOperation(prepared, reconcile);
            assertThrows(CompletionException.class, first::join);
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

            var second = coordinator.completePreparedExternalOperation(prepared, reconcile);
            assertThrows(CompletionException.class, second::join);
            assertEquals(2, reconciliations.get());
            assertEquals(3, purchaseCalls.get());
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

            when(persistence.saveNow(state)).thenReturn(true);
            when(persistence.hasPendingChanges(state)).thenReturn(false);
            assertSame(receipt, coordinator.completePreparedExternalOperation(prepared, reconcile).join());
            assertEquals(2, reconciliations.get());
            assertEquals(3, purchaseCalls.get());
            assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        }
    }
}
