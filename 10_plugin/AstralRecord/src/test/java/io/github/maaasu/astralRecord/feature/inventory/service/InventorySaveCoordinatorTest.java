package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateOutcomeUnknownException;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventorySaveCoordinatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 外部取引の反映後に保存がタイムアウトしても、再試行は保存だけを行い、受取Goldを再加算しない。
     */
    @Test
    void preparedRetryAfterSaveTimeoutDoesNotReapplyGoldDelta() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor, 1_000);
        AtomicInteger gold = new AtomicInteger(100);
        AtomicInteger applications = new AtomicInteger();
        java.util.function.Function<InventoryPersistence.PersistedInventoryBaseline, Integer> reconcile = baseline -> {
            applications.incrementAndGet();
            return gold.updateAndGet(current -> 110 + current - 100);
        };

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var prepare = coordinator.prepareExternalOperationAfterSave(accountId);
            executor.runAll();
            var prepared = prepare.join();
            var first = coordinator.completePreparedExternalOperation(prepared, reconcile);
            executor.runAll();
            CompletionException failure = assertThrows(CompletionException.class, first::join);
            assertTrue(failure.getCause() instanceof InventorySaveCoordinator.ExternalOperationTimeoutException);
            assertEquals(110, gold.get());
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

            when(persistence.saveNow(state)).thenReturn(true);
            var retry = coordinator.completePreparedExternalOperation(prepared, reconcile);
            executor.runAll();

            assertEquals(110, retry.join());
            assertEquals(110, gold.get());
            assertEquals(1, applications.get());
            assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 外部結果の照合自体が失敗した場合は、同じ handle で照合を再試行できる。
     */
    @Test
    void preparedRetryRepeatsReconciliationThatDidNotComplete() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        when(persistence.saveNow(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        AtomicInteger attempts = new AtomicInteger();
        java.util.function.Function<InventoryPersistence.PersistedInventoryBaseline, Void> reconcile = baseline -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("read unavailable");
            return null;
        };

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var prepare = coordinator.prepareExternalOperationAfterSave(accountId);
            executor.runAll();
            var prepared = prepare.join();
            var first = coordinator.completePreparedExternalOperation(prepared, reconcile);
            executor.runAll();
            assertThrows(CompletionException.class, first::join);
            verify(persistence, never()).saveNow(state);

            var retry = coordinator.completePreparedExternalOperation(prepared, reconcile);
            executor.runAll();

            assertNull(retry.join());
            assertEquals(2, attempts.get());
            assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ローカル操作の即時応答
     * 検証契約: 先行保存が未実行でも強化・回復のローカル結果を即時に返し、DB保存失敗で補償しない。
     */
    @Test
    void responsiveMutationCompletesWhileEarlierSaveIsQueued() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        var earlierSave = coordinator.saveNow(accountId);
        AtomicInteger value = new AtomicInteger(10);

        var first = coordinator.executeResponsiveMutation(accountId, () -> value.addAndGet(5));
        var second = coordinator.executeResponsiveMutation(accountId, () -> value.addAndGet(5));

        assertTrue(first.isDone());
        assertEquals(15, first.join());
        assertEquals(20, second.join());
        assertFalse(earlierSave.isDone());
        assertTrue(state.isDirty());
        verify(persistence, never()).saveNow(state);
        verify(persistence, never()).saveCriticalNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ローカル操作の即時応答
     * 検証契約: 外部取引が保留中のアカウントでは即時操作も消費・計算を実行しない。
     */
    @Test
    void responsiveMutationRejectsPendingExternalBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(
            mock(InventoryPersistence.class), registry, new ManualExecutor());
        coordinator.executeExclusiveAfterSave(accountId, ignored -> true);
        AtomicBoolean changed = new AtomicBoolean();

        var result = coordinator.executeResponsiveMutation(accountId, () -> changed.compareAndSet(false, true));

        assertThrows(CompletionException.class, result::join);
        assertFalse(changed.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 重要操作のACKと補償
     * 検証契約: 重要操作の結果不明では補償・再計算・排他解除を行わず、同一保存の確定後に成功する。
     */
    @Test
    void criticalUnknownOutcomeKeepsMutationAndBoundaryUntilAcknowledged() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        AtomicInteger mutationCalls = new AtomicInteger();
        AtomicBoolean rolledBack = new AtomicBoolean();
        AtomicInteger saveCalls = new AtomicInteger();
        when(persistence.saveCriticalNow(state)).thenAnswer(ignored -> {
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));
            assertFalse(rolledBack.get());
            if (saveCalls.incrementAndGet() == 1) throw new PlayerStateOutcomeUnknownException(null);
            return true;
        });

        var result = coordinator.executeCriticalMutation(accountId, () ->
            new InventorySaveCoordinator.CriticalMutation<>(mutationCalls.incrementAndGet(), () -> rolledBack.set(true)));
        executor.runAll();

        assertEquals(1, result.join());
        assertEquals(1, mutationCalls.get());
        assertEquals(2, saveCalls.get());
        assertFalse(rolledBack.get());
        assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 同一accountの未開始autosave/close save重複要求を1件へまとめる。
     */
    @Test
    void coalescesPendingStorageCloseSaves() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var first = coordinator.saveNow(accountId);
        var second = coordinator.saveNow(accountId);

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(1, executor.pendingCount());

        executor.runAll();

        assertTrue(first.join());
        assertTrue(second.join());
        verify(persistence, times(1)).saveNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 200ms以内に到着した通常autosaveを1回のsnapshot保存へ集約する。
     */
    @Test
    void coalescesAutosavesWithinDebounceWindow() throws Exception {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var first = coordinator.saveAuto(state);
        var second = coordinator.saveAuto(state);

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        awaitPendingTask(executor);
        executor.runAll();

        assertTrue(first.join());
        assertTrue(second.join());
        verify(persistence, times(1)).save(state, InventoryPersistence.SaveTrigger.AUTO);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: チャンネル境界保存はsnapshot中に増えた差分も再保存し、未保存差分が消えたACK後だけ成功する。
     */
    @Test
    void boundarySaveRetriesUntilNoPendingChangesRemain() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(true);
        when(persistence.hasPendingChanges(state)).thenReturn(true, true, false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var save = coordinator.saveForBoundary(accountId);
        executor.runAll();

        assertTrue(save.join());
        verify(persistence, times(3)).saveNow(state);
        assertSame(state, registry.get(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: チャンネル境界のsnapshot保存が失敗した場合はstateを解放せず移動失敗を返す。
     */
    @Test
    void boundarySaveFailureRetainsCurrentSessionState() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.markDirty();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var save = coordinator.saveForBoundary(accountId);
        executor.runAll();

        assertFalse(save.join());
        assertSame(state, registry.get(accountId));
        assertTrue(state.isDirty());
        verify(persistence, never()).clearAccount(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 重要操作の完成snapshotがSQL ACKを得られない場合は操作前状態へ補償して失敗確定する。
     */
    @Test
    void criticalSaveFailureRollsBackBeforeFailureCompletes() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        when(persistence.saveCriticalNow(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        int[] value = {10};

        var mutation = coordinator.executeCriticalMutation(accountId, () -> {
            value[0] = 20;
            return new InventorySaveCoordinator.CriticalMutation<>("changed", () -> value[0] = 10);
        });
        executor.runAll();

        assertThrows(CompletionException.class, mutation::join);
        assertEquals(10, value[0]);
        assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        verify(persistence).saveCriticalNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 完成snapshotのSQL確定をACKで判定できない場合は完成ローカル状態とblockを維持し、補償しない。
     */
    @Test
    void criticalAcknowledgementFailureDoesNotRollBackCommittedState() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNowWithBaseline(state)).thenReturn(
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()));
        when(persistence.saveCriticalNow(state)).thenThrow(new PlayerStateAcknowledgementException(
            new IllegalStateException("invalid completed acknowledgement")));
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        int[] value = {10};

        var mutation = coordinator.executeCriticalMutation(accountId, () -> {
            value[0] = 20;
            return new InventorySaveCoordinator.CriticalMutation<>("changed", () -> value[0] = 10);
        });
        executor.runAll();

        CompletionException failure = assertThrows(CompletionException.class, mutation::join);
        assertTrue(failure.getCause() instanceof PlayerStateAcknowledgementException);
        assertEquals(20, value[0]);
        assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        verify(persistence).saveCriticalNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: critical pre-saveのcapture中に通常変更が増えた場合は次便まで保存してから重要操作を開始する。
     */
    @Test
    void criticalMutationRetriesPreSaveUntilParticipantChangesAreStable() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
        when(persistence.saveNowWithBaseline(state)).thenReturn(null, baseline);
        when(persistence.saveCriticalNow(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor, 1_000L);
        AtomicBoolean changed = new AtomicBoolean();

        var mutation = coordinator.executeCriticalMutation(accountId, () ->
            new InventorySaveCoordinator.CriticalMutation<>(changed.compareAndSet(false, true), () -> changed.set(false)));
        executor.runAll();

        assertTrue(mutation.join());
        assertTrue(changed.get());
        verify(persistence, times(2)).saveNowWithBaseline(state);
        verify(persistence).saveCriticalNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: reconciliation境界を跨ぐsaveをcoalesceせず順序を維持する。
     */
    @Test
    void doesNotCoalesceSaveAcrossReconciliationBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        List<String> order = new ArrayList<>();
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            order.add("save");
            return true;
        });
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var beforeBoundary = coordinator.saveNow(accountId);
        var reconciliation = coordinator.enqueueLogoutReconciliation(accountId, () -> {
            order.add("reconciliation");
            return true;
        });
        var afterBoundary = coordinator.saveNow(accountId);

        executor.runAll();

        assertTrue(beforeBoundary.join());
        assertTrue(reconciliation.join());
        assertTrue(afterBoundary.join());
        assertEquals(List.of("save", "reconciliation", "save"), order);
        verify(persistence, times(2)).saveNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: close save失敗後もlogout saveを実行し、retry成功後だけstateを解放する。
     */
    @Test
    void runsLogoutAfterFailedCloseSaveAndReleasesStateOnlyAfterRetrySucceeds() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        List<String> order = new ArrayList<>();
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            order.add("close");
            state.restoreDirty();
            return false;
        });
        when(persistence.hasPendingChanges(state)).thenAnswer(invocation -> state.isDirty());
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var closeSave = coordinator.saveNow(accountId);
            var logoutSave = coordinator.saveOnLogout(accountId, state, () -> {
                order.add("logout");
                state.takeAndClearDirty();
            });

            assertSame(state, registry.get(accountId));
            assertFalse(closeSave.isDone());
            assertFalse(logoutSave.isDone());

            executor.runAll();

            assertFalse(closeSave.join());
            assertTrue(logoutSave.join());
            assertEquals(List.of("close", "logout"), order);
            assertNull(registry.get(accountId));
            verify(persistence).clearAccount(accountId);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_5-例外・ログ・運用.md
     * 章・見出し: # 08_5-例外・ログ・運用 > ## 3. 例外・運用方針
     * 検証契約: autosave失敗時にdirty stateをregistryへ保持しretry成功まで解放しない。
     */
    @Test
    void keepsDirtyStateAttachedUntilAutosaveRetrySucceeds() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.markDirty();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(state)).thenReturn(true, false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var logoutSave = coordinator.saveOnLogout(accountId, state, () -> {
            });

            executor.runAll();

            assertFalse(logoutSave.join());
            assertTrue(state.isDirty());
            assertSame(state, registry.get(accountId));
            verify(persistence, never()).clearAccount(accountId);

            state.takeAndClearDirty();
            coordinator.cleanupAfterRetry(state);

            assertNull(registry.get(accountId));
            verify(persistence).clearAccount(accountId);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 即時再loginはlogout完了を待ち、失敗retained stateを取得してstale API dataをloadしない。
     */
    @Test
    void quickRelogWaitsForLogoutAndClaimsFailedStateInsteadOfLoadingStaleData() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.markDirty();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var logoutSave = coordinator.saveOnLogout(accountId, state, () -> {
            });
            var priorSaves = coordinator.awaitQueuedSaves(accountId);

            assertFalse(priorSaves.isDone());
            executor.runAll();

            assertFalse(logoutSave.join());
            assertNull(priorSaves.join());
            InventorySaveCoordinator.RetainedStateLease firstLease = coordinator.claimRetainedState(accountId);
            InventorySaveCoordinator.RetainedStateLease relogLease = coordinator.claimRetainedState(accountId);
            assertNotNull(firstLease);
            assertNotNull(relogLease);
            assertSame(state, firstLease.state());
            assertSame(state, relogLease.state());
            assertTrue(relogLease.generation() > firstLease.generation());
            assertFalse(coordinator.releaseRetainedStateLease(firstLease));
            assertTrue(coordinator.releaseRetainedStateLease(relogLease));
            assertSame(state, registry.get(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: アカウント切替用バリアは先行logout保存の失敗を伝播し、切替処理を続行させない。
     */
    @Test
    void accountSwitchBarrierPropagatesFailedPriorLogoutSave() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var logoutSave = coordinator.saveOnLogoutWithResult(accountId, state, () -> false);
            var switchBarrier = coordinator.awaitQueuedSavesOrThrow(accountId);

            executor.runAll();

            assertFalse(logoutSave.join());
            assertThrows(CompletionException.class, switchBarrier::join);
            assertSame(state, registry.get(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 同一accountのimmediate/autosave/logoutを単一laneで直列化する。
     */
    @Test
    void serializesImmediateAutoAndLogoutSavesForSameAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch immediateStarted = new CountDownLatch(1);
        CountDownLatch releaseImmediate = new CountDownLatch(1);
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            order.add("immediate");
            immediateStarted.countDown();
            assertTrue(releaseImmediate.await(2, TimeUnit.SECONDS));
            state.takeAndClearDirty();
            return true;
        });
        when(persistence.save(state, InventoryPersistence.SaveTrigger.AUTO)).thenAnswer(invocation -> {
            order.add("auto");
            state.takeAndClearDirty();
            return true;
        });
        when(persistence.hasPendingChanges(state)).thenAnswer(invocation -> state.isDirty());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try {
            var immediateSave = coordinator.saveNow(accountId);
            assertTrue(immediateStarted.await(2, TimeUnit.SECONDS));
            var autoSave = coordinator.saveAuto(state);
            var logoutSave = coordinator.saveOnLogout(accountId, state, () -> order.add("logout"));
            var queuedSaves = coordinator.awaitQueuedSaves(accountId);

            releaseImmediate.countDown();

            assertTrue(immediateSave.get(2, TimeUnit.SECONDS));
            assertTrue(autoSave.get(2, TimeUnit.SECONDS));
            assertTrue(logoutSave.get(2, TimeUnit.SECONDS));
            assertNull(queuedSaves.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("immediate", "auto", "logout"), order);
            assertNull(registry.get(accountId));
        } finally {
            releaseImmediate.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: reconciliation失敗後も現sessionのlogout保存を継続する。
     */
    @Test
    void continuesLogoutSaveAfterReconciliationFailure() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        List<String> order = new ArrayList<>();

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var reconciliation = coordinator.enqueueLogoutReconciliation(accountId, () -> {
                order.add("reconciliation");
                throw new IllegalStateException("failure");
            });
            var logoutSave = coordinator.saveOnLogout(accountId, state, () -> order.add("logout"));

            executor.runAll();

            assertThrows(CompletionException.class, reconciliation::join);
            assertTrue(logoutSave.join());
            assertEquals(List.of("reconciliation", "logout"), order);
            assertNull(registry.get(accountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: pending write待機自身を保存laneへ登録せず自己待機しない。
     */
    @Test
    void pendingWriteWaitDoesNotCreateOrFollowItsOwnLane() {
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        assertTrue(coordinator.awaitPendingWrites(100));
        assertEquals(0, executor.pendingCount());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: closing drain後のlate saveを拒否しdirty flagを消さない。
     */
    @Test
    void rejectsLateSaveAfterClosingDrainWithoutClearingDirtyState() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        coordinator.beginClosing();
        assertTrue(coordinator.awaitPendingWrites(100));

        var rejectedSave = coordinator.saveNow(accountId);

        assertTrue(rejectedSave.isDone());
        assertFalse(rejectedSave.join());
        assertTrue(state.isDirty());
        assertEquals(0, executor.pendingCount());
        verify(persistence, never()).saveNow(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: closing後に拒否された外部操作は未開始として例外完了し、未解決境界を残さない。
     */
    @Test
    void rejectedLateExternalOperationDoesNotLeakUnresolvedBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        AtomicBoolean operationCalled = new AtomicBoolean();

        coordinator.beginClosing();
        var rejected = coordinator.executeExclusiveAfterSave(accountId, baseline -> {
            operationCalled.set(true);
            return "APPLIED";
        });

        assertTrue(rejected.isDone());
        assertThrows(CompletionException.class, rejected::join);
        assertFalse(operationCalled.get());
        assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
        assertEquals(0, executor.pendingCount());
        verify(persistence, never()).saveNowWithBaseline(state);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: オーブ操作jobは同一account laneで事前保存後にPOST・同一ID照会再送・影響entry照合を終え、その後で後続autosaveとlogout保存を開始する。
     */
    @Test
    void exclusiveOrbOperationCompletesReconciliationBeforeLaterSaveAndLogout() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        List<String> order = new ArrayList<>();
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            order.add("pre-save");
            state.takeAndClearDirty();
            return true;
        });
        when(persistence.save(state, InventoryPersistence.SaveTrigger.AUTO)).thenAnswer(invocation -> {
            order.add("auto-save");
            state.takeAndClearDirty();
            return true;
        });
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        UUID operationId = UUID.randomUUID();

        var operation = coordinator.executeExclusiveAfterSave(accountId, () -> {
            order.add("post:" + operationId);
            order.add("get:" + operationId);
            order.add("retry:" + operationId);
            order.add("reconcile");
            return "APPLIED";
        });
        var autoSave = coordinator.saveAuto(state);
        var logout = coordinator.saveOnLogout(accountId, state, () -> order.add("logout"));

        executor.runAll();

        assertEquals("APPLIED", operation.join());
        assertTrue(autoSave.join());
        assertTrue(logout.join());
        assertEquals(List.of(
            "pre-save",
            "post:" + operationId,
            "get:" + operationId,
            "retry:" + operationId,
            "reconcile",
            "auto-save",
            "logout"
        ), order);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: オーブ操作前のinventory保存が失敗した場合は外部操作もローカル支払い変更も行わず、同じlaneの後続logout保存は継続する。
     */
    @Test
    void failedPreSavePreventsOrbOperationWithoutLocalAheadConsumption() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(false);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        AtomicBoolean operationCalled = new AtomicBoolean();
        int[] localOrbQuantity = {2};
        List<String> order = new ArrayList<>();

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var operation = coordinator.executeExclusiveAfterSave(accountId, () -> {
                operationCalled.set(true);
                localOrbQuantity[0]--;
                return "APPLIED";
            });
            var logout = coordinator.saveOnLogout(accountId, state, () -> order.add("logout"));

            executor.runAll();

            assertThrows(CompletionException.class, operation::join);
            assertFalse(operationCalled.get());
            assertEquals(2, localOrbQuantity[0]);
            assertTrue(logout.join());
            assertEquals(List.of("logout"), order);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: shutdown drain開始前に受理したオーブ操作はclosing後も同一lane内で最後まで実行し、影響entry照合を完了してからdrainを完了する。
     */
    @Test
    void shutdownDrainWaitsForAlreadyAcceptedOrbOperation() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);
        List<String> order = new ArrayList<>();

        var operation = coordinator.executeExclusiveAfterSave(accountId, () -> {
            order.add("operation");
            order.add("reconcile");
            return "APPLIED";
        });
        coordinator.beginClosing();

        assertFalse(operation.isDone());
        executor.runAll();

        assertEquals("APPLIED", operation.join());
        assertEquals(List.of("operation", "reconcile"), order);
        assertTrue(coordinator.awaitPendingWrites(100));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 古い prepared handle の遅延破棄は、同一accountで後から開始した外部操作の境界を解除しない。
     */
    @Test
    void stalePreparedCleanupCannotReleaseNewExternalOperationBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
        when(persistence.saveNowWithBaseline(state)).thenReturn(baseline);
        when(persistence.saveNow(state)).thenReturn(true);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var preparedFuture = coordinator.prepareExternalOperationAfterSave(accountId);
        executor.runAll();
        InventorySaveCoordinator.PreparedExternalOperation prepared = preparedFuture.join();
        coordinator.abandonPreparedExternalOperation(prepared);

        var nextOperation = coordinator.executeExclusiveAfterSave(accountId, () -> "next");
        coordinator.abandonPreparedExternalOperation(prepared);

        assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));
        executor.runAll();
        assertEquals("next", nextOperation.join());
        assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 三者マージ後保存中に同account stateが再変更された場合は2回目の保存までunresolved境界を保持し、先行受理済みlogoutとshutdown drainをその後にだけ完了する。
     */
    @Test
    void postMergeSaveLatchRetriesBeforeUnresolvedLogoutAndShutdownRelease() throws Exception {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
        when(persistence.saveNowWithBaseline(state)).thenAnswer(invocation -> {
            state.takeAndClearDirty();
            return baseline;
        });
        CountDownLatch firstPostSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPostSave = new CountDownLatch(1);
        AtomicInteger postSaveCalls = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            int call = postSaveCalls.incrementAndGet();
            order.add("post-save-" + call);
            state.takeAndClearDirty();
            if (call == 1) {
                firstPostSaveStarted.countDown();
                assertTrue(releaseFirstPostSave.await(2, TimeUnit.SECONDS));
            }
            return !state.isDirty();
        });
        when(persistence.hasPendingChanges(state)).thenAnswer(invocation -> state.isDirty());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        try {
            var operation = coordinator.executeExclusiveAfterSave(accountId, savedBaseline -> {
                assertSame(baseline, savedBaseline);
                order.add("merge");
                state.markDirty();
                return "APPLIED";
            });
            assertTrue(firstPostSaveStarted.await(2, TimeUnit.SECONDS));
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

            var logout = coordinator.saveOnLogout(
                accountId,
                state,
                () -> order.add("logout")
            );
            coordinator.beginClosing();
            assertFalse(operation.isDone());
            assertFalse(logout.isDone());

            // 1回目のpost-saveがsnapshotを取得した後の変更。dirtyを残して再保存を要求する。
            state.markDirty();
            releaseFirstPostSave.countDown();

            assertEquals("APPLIED", operation.get(2, TimeUnit.SECONDS));
            assertTrue(logout.get(2, TimeUnit.SECONDS));
            assertTrue(coordinator.awaitPendingWrites(2_000));
            assertEquals(2, postSaveCalls.get());
            assertEquals(List.of("merge", "post-save-1", "post-save-2", "logout"), order);
            assertFalse(coordinator.hasUnresolvedExternalOperation(accountId));
            assertNull(registry.get(accountId));
            verify(persistence).clearAccount(accountId);
        } finally {
            releaseFirstPostSave.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: terminal未確認の外部操作が例外終了した場合はunresolved境界を保持し、即時保存・autosave・logout・cleanupがstale stateを永続化または解放しない。
     */
    @Test
    void unresolvedExternalOperationBlocksEverySaveAndCleanupBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(true);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        var operation = coordinator.executeExclusiveAfterSave(accountId, () -> {
            throw new IllegalStateException("terminal unknown");
        });
        executor.runAll();

        assertThrows(CompletionException.class, operation::join);
        assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));

        var immediate = coordinator.saveNow(accountId);
        var auto = coordinator.saveAuto(state);
        var logout = coordinator.saveOnLogout(accountId, state, () -> {
            throw new AssertionError("logout must remain blocked");
        });
        executor.runAll();
        coordinator.cleanupAfterRetry(state);

        assertFalse(immediate.join());
        assertFalse(auto.join());
        assertFalse(logout.join());
        assertSame(state, registry.get(accountId));
        assertTrue(state.isDirty());
        verify(persistence, times(1)).saveNow(state);
        verify(persistence, never()).save(state, InventoryPersistence.SaveTrigger.AUTO);
        verify(persistence, never()).clearAccount(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 外部操作後の正本保存が失敗し続けても、有限時間で future を失敗完了し、未解決境界を保持する。
     */
    @Test
    void timesOutPostExternalSaveWhileKeepingUnresolvedBoundary() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
        when(persistence.saveNowWithBaseline(state)).thenReturn(baseline);
        when(persistence.saveNow(state)).thenReturn(false);
        when(persistence.hasPendingChanges(state)).thenReturn(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(
            persistence,
            registry,
            executor,
            1_000L
        );

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            AtomicBoolean operationCalled = new AtomicBoolean();
            var operation = coordinator.executeExclusiveAfterSave(accountId, savedBaseline -> {
                operationCalled.set(true);
                assertSame(baseline, savedBaseline);
                return "APPLIED";
            });

            CompletionException failure = assertThrows(CompletionException.class, operation::join);

            assertTrue(failure.getCause() instanceof InventorySaveCoordinator.ExternalOperationTimeoutException);
            assertTrue(operationCalled.get());
            assertTrue(coordinator.hasUnresolvedExternalOperation(accountId));
            assertTrue(state.isDirty());
            verify(persistence).saveNowWithBaseline(state);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int pendingCount() {
            return tasks.size();
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }

    private static void awaitPendingTask(ManualExecutor executor) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.pendingCount() == 0 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5L);
        }
        assertTrue(executor.pendingCount() > 0, "Debounced autosave was not dispatched within 2 seconds");
    }
}
