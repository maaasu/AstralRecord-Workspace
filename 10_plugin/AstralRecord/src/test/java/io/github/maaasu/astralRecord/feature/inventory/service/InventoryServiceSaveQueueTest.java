package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryServiceSaveQueueTest {

    @Test
    void immediateSaveJoinsPendingSaveWithoutWritingOutsideTheAccountQueue() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.saveNow(state)).thenReturn(true);
        var queued = new ArrayDeque<Runnable>();
        var coordinator = new InventorySaveCoordinator(persistence, registry, queued::addLast, 1_000L);
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            persistence,
            coordinator
        );

        var first = coordinator.saveNow(accountId);
        var second = service.saveNow(accountId);

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        verifyNoInteractions(persistence);
        assertEquals(1, queued.size());

        queued.removeFirst().run();

        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertTrue(first.join());
        assertTrue(second.join());
        verify(persistence).saveNow(state);
    }

    /**
     * prepared外部操作が例外終了した場合は境界を明示的に破棄し、次回保存・操作を恒久的に塞がない。
     */
    @Test
    void abandonsPreparedExternalBoundaryWhenMutationFails() {
        UUID accountId = UUID.randomUUID();
        InventorySaveCoordinator saveCoordinator = mock(InventorySaveCoordinator.class);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventorySaveCoordinator.PreparedExternalOperation prepared =
            new InventorySaveCoordinator.PreparedExternalOperation(
                accountId,
                state,
                new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of()),
                UUID.randomUUID()
            );
        when(saveCoordinator.prepareExternalOperationAfterSave(accountId))
            .thenReturn(CompletableFuture.completedFuture(prepared));
        when(saveCoordinator.completePreparedExternalOperation(
            eq(prepared),
            org.mockito.ArgumentMatchers
                .<Function<InventoryPersistence.PersistedInventoryBaseline, String>>any()
        ))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("terminal unknown")));
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            new PlayerInventoryStateRegistry(),
            mock(InventoryPersistence.class),
            saveCoordinator
        );

        CompletableFuture<String> failed = service.executeExternalMutationAfterSave(
            accountId,
            () -> "unused"
        );

        assertThrows(CompletionException.class, failed::join);
        verify(saveCoordinator).abandonPreparedExternalOperation(prepared);
    }
}
