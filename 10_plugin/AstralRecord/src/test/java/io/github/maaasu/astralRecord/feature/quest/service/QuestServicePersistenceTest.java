package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestServicePersistenceTest extends MockBukkitTestBase {

    @Test
    void stopFallsBackToSynchronousSaveWhenAsyncExecutorRejectsShutdownTask() {
        List<QuestPlayerState> savedStates = new CopyOnWriteArrayList<>();
        AtomicInteger submittedTasks = new AtomicInteger();
        QuestHarness harness = harness(command -> {
            if (submittedTasks.getAndIncrement() == 0) {
                command.run();
                return;
            }
            throw new RejectedExecutionException("executor is shutting down");
        });
        doAnswer(invocation -> {
            savedStates.add(invocation.getArgument(0, QuestPlayerState.class).snapshot());
            return null;
        }).when(harness.stateRepository()).save(any(QuestPlayerState.class));

        AstPlayer player = player();
        when(harness.statusService().getStatus(player)).thenReturn(player.getStatusSnapshot());
        harness.service().applyInitialState(emptyState(player));
        assertTrue(harness.service().accept(player, harness.quest(), null));

        assertDoesNotThrow(harness.service()::stop);
        assertTrue(savedStates.stream().anyMatch(state -> state.activeQuests().containsKey(harness.quest().id())));
    }

    @Test
    void quickRelogUsesRetainedStateWithoutReadingStaleDisk() throws Exception {
        ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        try {
            QuestHarness harness = harness(persistenceExecutor);
            doAnswer(invocation -> {
                saveStarted.countDown();
                assertTrue(releaseSave.await(5, TimeUnit.SECONDS));
                return null;
            }).when(harness.stateRepository()).save(any(QuestPlayerState.class));

            AstPlayer player = player();
            when(harness.statusService().getStatus(player)).thenReturn(player.getStatusSnapshot());
            QuestPlayerState initial = emptyState(player);
            harness.service().applyInitialState(initial);
            assertTrue(harness.service().accept(player, harness.quest(), null));

            UUID accountId = player.getAccount().getUuid();
            harness.service().releaseState(accountId);
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));

            QuestService.InitialState relog = harness.service().loadInitialState(accountId);
            assertTrue(relog.state().activeQuests().containsKey(harness.quest().id()));
            assertTrue(harness.service().applyInitialState(relog));
            verify(harness.stateRepository(), never()).load(accountId);

            releaseSave.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), harness.service()::stop);
        } finally {
            releaseSave.countDown();
            persistenceExecutor.shutdownNow();
        }
    }

    @Test
    void stopChainsLatestGenerationBehindInFlightSave() throws Exception {
        ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor();
        ExecutorService stopExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch firstSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        CountDownLatch stopStarted = new CountDownLatch(1);
        List<QuestPlayerState> savedStates = new CopyOnWriteArrayList<>();
        AtomicInteger concurrentSaves = new AtomicInteger();
        AtomicInteger maximumConcurrentSaves = new AtomicInteger();
        try {
            QuestHarness harness = harness(persistenceExecutor);
            AtomicInteger saveCount = new AtomicInteger();
            doAnswer(invocation -> {
                QuestPlayerState snapshot = invocation.getArgument(0, QuestPlayerState.class).snapshot();
                int concurrent = concurrentSaves.incrementAndGet();
                maximumConcurrentSaves.accumulateAndGet(concurrent, Math::max);
                try {
                    if (saveCount.getAndIncrement() == 0) {
                        firstSaveStarted.countDown();
                        assertTrue(releaseFirstSave.await(5, TimeUnit.SECONDS));
                    }
                    savedStates.add(snapshot);
                    return null;
                } finally {
                    concurrentSaves.decrementAndGet();
                }
            }).when(harness.stateRepository()).save(any(QuestPlayerState.class));

            AstPlayer player = player();
            when(harness.statusService().getStatus(player)).thenReturn(player.getStatusSnapshot());
            UUID accountId = player.getAccount().getUuid();
            harness.service().applyInitialState(emptyState(player));
            assertTrue(harness.service().accept(player, harness.quest(), null));
            harness.service().releaseState(accountId);
            assertTrue(firstSaveStarted.await(5, TimeUnit.SECONDS));

            QuestService.InitialState relog = harness.service().loadInitialState(accountId);
            assertTrue(harness.service().applyInitialState(relog));
            assertTrue(harness.service().abandon(player, harness.quest().id()));

            CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> {
                stopStarted.countDown();
                harness.service().stop();
            }, stopExecutor);
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
            assertFalse(stopping.isDone());
            assertTrue(maximumConcurrentSaves.get() <= 1);

            releaseFirstSave.countDown();
            stopping.get(5, TimeUnit.SECONDS);

            assertTrue(savedStates.size() >= 2);
            assertTrue(savedStates.get(0).activeQuests().containsKey(harness.quest().id()));
            assertFalse(savedStates.get(savedStates.size() - 1).activeQuests().containsKey(harness.quest().id()));
            assertTrue(maximumConcurrentSaves.get() <= 1);
        } finally {
            releaseFirstSave.countDown();
            persistenceExecutor.shutdownNow();
            stopExecutor.shutdownNow();
        }
    }

    private QuestHarness harness(Executor persistenceExecutor) {
        QuestDefinition quest = new QuestDefinition(
            "persistence_test",
            "persistence_test",
            List.of(),
            Material.PAPER,
            QuestRepeatMode.ONCE,
            0L,
            QuestCompletionMode.NPC,
            null,
            List.of(new QuestObjectiveDefinition("kill", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestDefinitionRepository questRepository = mock(QuestDefinitionRepository.class);
        QuestBoardRepository boardRepository = mock(QuestBoardRepository.class);
        QuestPlayerStateRepository stateRepository = mock(QuestPlayerStateRepository.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountService accountService = mock(AccountService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        when(questRepository.findAll()).thenReturn(List.of(quest));
        when(boardRepository.findAll()).thenReturn(List.<QuestBoardDefinition>of());

        QuestService service = new QuestService(
            null,
            questRepository,
            boardRepository,
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService,
            persistenceExecutor,
            Runnable::run
        );
        service.loadAll();
        return new QuestHarness(quest, stateRepository, statusService, service);
    }

    private AstPlayer player() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(
            Map.of(StatusType.QUEST_LIMIT, 3.0D),
            100.0D,
            0.0D,
            0.0D
        ));
        return player;
    }

    private QuestPlayerState emptyState(AstPlayer player) {
        return new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
    }

    private record QuestHarness(
        QuestDefinition quest,
        QuestPlayerStateRepository stateRepository,
        StatusService statusService,
        QuestService service
    ) {
    }
}
