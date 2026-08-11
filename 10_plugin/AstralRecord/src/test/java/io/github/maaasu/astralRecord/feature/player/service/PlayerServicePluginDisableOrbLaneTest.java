package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServicePluginDisableOrbLaneTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 全オンラインプレイヤー保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: onDisable第一段で受理済みオーブ操作のpre-save・terminal確定・affected entry照合をdrainし、解決済みaccountだけmain threadで表示rebase後にPLUGIN_DISABLE保存してstateと両cacheを解放する。
     */
    @Test
    void resolvedOrbOperationDrainsBeforeDisableRebaseSaveAndSuccessfulClear() {
        ManualExecutor executor = new ManualExecutor();
        Harness harness = new Harness(executor);
        var operation = harness.coordinator.executeExclusiveAfterSave(
            harness.accountId,
            () -> {
                harness.order.add("terminal");
                harness.order.add("reconcile");
                return true;
            }
        );

        assertFalse(operation.isDone());
        assertSame(harness.state, harness.registry.get(harness.accountId));
        assertSame(harness.astPlayer, AstPlayerCache.get(harness.player));

        executor.runAll();
        assertTrue(operation.join());
        assertTrue(harness.coordinator.awaitPendingWrites(100));

        List<CompletableFuture<Boolean>> disableSaves =
            harness.playerService.saveAllOnlinePlayersAndClear();
        harness.coordinator.beginClosing();
        assertFalse(disableSaves.getFirst().isDone());
        executor.runAll();

        assertTrue(disableSaves.getFirst().join());
        assertEquals(List.of(
            "pre-save", "terminal", "reconcile", "refresh-display",
            "prepare-disable", "save-disable", "clear-account"
        ), harness.order);
        assertNull(harness.registry.get(harness.accountId));
        assertNull(AstPlayerCache.get(harness.player));
        verify(harness.playerSaveCoordinator).prepare(
            harness.astPlayer,
            PlayerSaveTrigger.PLUGIN_DISABLE
        );
        verify(harness.playerSaveCoordinator).save(
            harness.astPlayer,
            PlayerSaveTrigger.PLUGIN_DISABLE
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 全オンラインプレイヤー保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: API停止で受理済みオーブ操作が第一段drain timeoutを超えたaccountは、DB原子操作を正本としてstaleなPLUGIN_DISABLE保存・表示rebase・state/cache clearをすべてスキップする。
     */
    @Test
    void disableDrainTimeoutSkipsStaleSaveRebaseAndClearForUnresolvedAccount() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Harness harness = new Harness(executor);
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        try {
            var operation = harness.coordinator.executeExclusiveAfterSave(
                harness.accountId,
                () -> {
                    harness.order.add("transport-pending");
                    operationStarted.countDown();
                    try {
                        assertTrue(releaseOperation.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    harness.order.add("reconcile");
                    return true;
                }
            );
            assertTrue(operationStarted.await(2, TimeUnit.SECONDS));

            assertFalse(harness.coordinator.awaitPendingWrites(20));
            List<CompletableFuture<Boolean>> disableSaves =
                harness.playerService.saveAllOnlinePlayersAndClear();
            harness.coordinator.beginClosing();

            assertTrue(disableSaves.isEmpty());
            assertSame(harness.state, harness.registry.get(harness.accountId));
            assertSame(harness.astPlayer, AstPlayerCache.get(harness.player));
            verify(harness.persistence, never()).clearAccount(harness.accountId);
            verify(harness.inventoryService, never())
                .refreshEquipmentDisplaysForSave(harness.astPlayer);
            verify(harness.playerSaveCoordinator, never()).prepare(
                harness.astPlayer,
                PlayerSaveTrigger.PLUGIN_DISABLE
            );
            verify(harness.playerSaveCoordinator, never()).save(
                harness.astPlayer,
                PlayerSaveTrigger.PLUGIN_DISABLE
            );

            releaseOperation.countDown();
            assertTrue(operation.get(2, TimeUnit.SECONDS));
            assertTrue(harness.coordinator.awaitPendingWrites(100));
            assertSame(harness.state, harness.registry.get(harness.accountId));
            assertSame(harness.astPlayer, AstPlayerCache.get(harness.player));
            assertFalse(harness.order.contains("save-disable"));
            assertFalse(harness.order.contains("clear-account"));
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 全オンラインプレイヤー保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: PLUGIN_DISABLE保存が楽観競合でpendingを残した場合はfalseを返し、inventory state・persistence cache・AstPlayer cacheを保持する。
     */
    @Test
    void disableSaveConflictNeverClearsPendingStateOrCaches() {
        Harness harness = new Harness(Runnable::run);
        harness.saveConflict.set(true);

        var disableSave = harness.playerService
            .saveAllOnlinePlayersAndClear().getFirst();

        assertFalse(disableSave.join());
        assertSame(harness.state, harness.registry.get(harness.accountId));
        assertSame(harness.astPlayer, AstPlayerCache.get(harness.player));
        verify(harness.persistence, never()).clearAccount(harness.accountId);
        assertTrue(harness.coordinator.claimRetainedState(harness.accountId) != null);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 全オンラインプレイヤー保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: PLUGIN_DISABLE保存callbackが例外終了した場合もfutureへ失敗を伝播し、inventory state・persistence cache・AstPlayer cacheを先にclearしない。
     */
    @Test
    void disableSaveExceptionNeverClearsPendingStateOrCaches() {
        Harness harness = new Harness(Runnable::run);
        doThrow(new IllegalStateException("disable save failed"))
            .when(harness.playerSaveCoordinator)
            .save(harness.astPlayer, PlayerSaveTrigger.PLUGIN_DISABLE);

        var disableSave = harness.playerService
            .saveAllOnlinePlayersAndClear().getFirst();

        assertTrue(disableSave.isCompletedExceptionally());
        assertSame(harness.state, harness.registry.get(harness.accountId));
        assertSame(harness.astPlayer, AstPlayerCache.get(harness.player));
        verify(harness.persistence, never()).clearAccount(harness.accountId);
        assertTrue(harness.coordinator.claimRetainedState(harness.accountId) != null);
    }

    private final class Harness {
        private final org.mockbukkit.mockbukkit.entity.PlayerMock player = server().addPlayer();
        private final AstPlayer astPlayer =
            DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        private final UUID accountId = astPlayer.getAccount().getUuid();
        private final PlayerInventoryState state = new PlayerInventoryState(accountId);
        private final PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        private final InventoryPersistence persistence = mock(InventoryPersistence.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final PlayerSaveCoordinator playerSaveCoordinator = mock(PlayerSaveCoordinator.class);
        private final AtomicBoolean saveConflict = new AtomicBoolean();
        private final List<String> order = new ArrayList<>();
        private final InventorySaveCoordinator coordinator;
        private final PlayerService playerService;

        private Harness(Executor executor) {
            AstPlayerCache.put(astPlayer);
            registry.put(state);
            when(persistence.saveNow(state)).thenAnswer(invocation -> {
                order.add("pre-save");
                return true;
            });
            when(persistence.hasPendingChanges(state))
                .thenAnswer(invocation -> saveConflict.get());
            doAnswer(invocation -> {
                order.add("clear-account");
                return null;
            }).when(persistence).clearAccount(accountId);
            doAnswer(invocation -> {
                order.add("refresh-display");
                return null;
            }).when(inventoryService).refreshEquipmentDisplaysForSave(astPlayer);
            doAnswer(invocation -> {
                order.add("prepare-disable");
                return null;
            }).when(playerSaveCoordinator).prepare(
                astPlayer,
                PlayerSaveTrigger.PLUGIN_DISABLE
            );
            doAnswer(invocation -> {
                order.add("save-disable");
                return null;
            }).when(playerSaveCoordinator).save(
                astPlayer,
                PlayerSaveTrigger.PLUGIN_DISABLE
            );
            coordinator = new InventorySaveCoordinator(persistence, registry, executor);
            playerService = new PlayerService(
                mock(UserService.class),
                mock(AccountService.class),
                inventoryService,
                coordinator,
                persistence,
                registry,
                mock(StatusService.class),
                playerSaveCoordinator,
                mock(PlayerRegionService.class)
            );
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        private void runAll() {
            Runnable next;
            while ((next = pending.poll()) != null) {
                next.run();
            }
        }
    }
}
