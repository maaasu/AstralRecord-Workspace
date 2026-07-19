package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Test
    void pendingWriteWaitDoesNotCreateOrFollowItsOwnLane() {
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(persistence, registry, executor);

        assertTrue(coordinator.awaitPendingWrites(100));
        assertEquals(0, executor.pendingCount());
    }

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
}
