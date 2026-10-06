package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCompletionRewardStatus;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideCompletionRewardRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuideCompletionRewardServiceTest {
    @AfterEach
    void clearPlayers() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 状態表示と再照会
     * 検証契約: 進捗未ロードまたは未完了なら保存・GETを起動せず、受取可能表示を出さない。
     */
    @Test
    void unloadedOrIncompleteProgressNeverQueriesReward() {
        Fixture fixture = new Fixture();
        fixture.service.refresh(fixture.player, true, () -> { });
        assertFalse(fixture.service.canClaim(fixture.accountId));
        assertTrue(fixture.pending.isEmpty());

        when(fixture.guideService.isProgressLoaded(fixture.accountId)).thenReturn(true);
        fixture.service.refresh(fixture.player, true, () -> { });
        assertFalse(fixture.service.canClaim(fixture.accountId));
        assertTrue(fixture.pending.isEmpty());
        verify(fixture.repository, never()).find(fixture.accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 状態表示と再照会
     * 検証契約: 未設定・受取済みは案内とボタンを出さず、受取可能な場合だけ保存後に一度案内する。
     */
    @Test
    void statusAfterSaveControlsButtonAndOneTimeAnnouncement() {
        Fixture fixture = new Fixture();
        fixture.completedLocally();
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(false, true, false, false))
            .thenReturn(fixture.status(true, true, true, false))
            .thenReturn(fixture.status(true, true, false, true));

        fixture.service.refresh(fixture.player, true, () -> { });
        verify(fixture.repository, never()).find(fixture.accountId);
        assertFalse(fixture.service.canClaim(fixture.accountId));
        fixture.completeNext();
        assertFalse(fixture.service.canClaim(fixture.accountId));

        fixture.service.refresh(fixture.player, true, () -> { });
        fixture.completeNext();
        assertFalse(fixture.service.canClaim(fixture.accountId));

        fixture.service.refresh(fixture.player, true, () -> { });
        fixture.completeNext();
        assertTrue(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5220);

        fixture.service.refresh(fixture.player, true, () -> { });
        fixture.completeNext();
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5220);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: 同時クリックは一つの保存境界とPOSTへ合流し、保存前のAPI受取を行わない。
     */
    @Test
    void doubleClickClaimsOnlyOnceAfterSave() {
        Fixture fixture = new Fixture();
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenReturn(fixture.status(true, true, true, false));

        fixture.service.claim(fixture.player, () -> { });
        fixture.service.claim(fixture.player, () -> { });
        assertEquals(1, fixture.pending.size());
        verify(fixture.repository, never()).claim(fixture.accountId, fixture.userId);
        fixture.completeNext();

        verify(fixture.repository).claim(fixture.accountId, fixture.userId);
        assertFalse(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5221);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: player-state保存失敗ではPOSTを実行せず、状態を再照会して再試行可能にする。
     */
    @Test
    void saveFailureNeverPostsClaim() {
        Fixture fixture = new Fixture();
        fixture.ready();
        fixture.service.claim(fixture.player, () -> { });
        fixture.failNext();
        verify(fixture.repository, never()).claim(fixture.accountId, fixture.userId);
        assertEquals(1, fixture.pending.size());
        fixture.completeNext();
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5222);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: POST応答が不明でもGETで受取済みを見つけ、二重配布を要求せずメールを案内する。
     */
    @Test
    void unknownClaimOutcomeReconcilesByGet() {
        Fixture fixture = new Fixture();
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenThrow(new IllegalStateException("response lost"));
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(true, true, true, false));

        fixture.service.claim(fixture.player, () -> { });
        fixture.completeNext();
        assertEquals(1, fixture.pending.size());
        fixture.completeNext();
        assertFalse(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5221);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### セッション切替
     * 検証契約: 旧セッションの非同期応答は再ログイン後の新しい照会を上書きしない。
     */
    @Test
    void oldSessionResponseIsDiscarded() {
        Fixture fixture = new Fixture();
        fixture.completedLocally();
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(true, true, false, true));
        fixture.service.refresh(fixture.player, true, () -> { });
        fixture.service.discardAccountState(fixture.accountId);
        AstPlayer nextSession = mock(AstPlayer.class);
        when(nextSession.getBukkit()).thenReturn(fixture.player);
        when(nextSession.getAccount()).thenReturn(fixture.account);
        AstPlayerCache.put(nextSession);
        fixture.service.refresh(fixture.player, true, () -> { });
        assertEquals(2, fixture.pending.size());
        fixture.completeNext();
        assertFalse(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages, never()).send(fixture.player, PlayerMsgId.P_5220);
        fixture.completeNext();
        assertTrue(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5220);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 状態表示と再照会
     * 検証契約: GET 通信失敗でも実 coordinator の外部境界を閉じ、次の GET が再試行できる。
     */
    @Test
    void failedGetDoesNotStrandRealCoordinatorBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.completedLocally();
        when(fixture.repository.find(fixture.accountId))
            .thenThrow(new IllegalStateException("GET failed"))
            .thenReturn(fixture.status(true, true, false, true));

        fixture.service.refresh(fixture.player, false, () -> { });
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        fixture.service.refresh(fixture.player, false, () -> { });
        assertTrue(fixture.service.canClaim(fixture.accountId));
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: POST 応答不明後の GET が claimed=false でも境界を閉じ、同じセッションで再請求できる。
     */
    @Test
    void unknownPostAndUnclaimedGetPermitRetryWithRealCoordinator() {
        Fixture fixture = new Fixture(true);
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenThrow(new IllegalStateException("POST response lost"))
            .thenReturn(fixture.status(true, true, true, false));
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(true, true, false, true));

        fixture.service.claim(fixture.player, () -> { });
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertTrue(fixture.service.canClaim(fixture.accountId));
        fixture.service.claim(fixture.player, () -> { });
        assertFalse(fixture.service.canClaim(fixture.accountId));
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5221);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: POST 後保存失敗で残った境界は同じ操作 ID の recovery GET が claimed=true を確認して閉じる。
     */
    @Test
    void postSaveFailureRecoversOwnedBoundaryWithRealCoordinator() {
        Fixture fixture = new Fixture(true);
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenReturn(fixture.status(true, true, true, false));
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(true, true, true, false));
        fixture.failNextPostSave.set(true);

        fixture.service.claim(fixture.player, () -> { });
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertFalse(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5221);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 状態表示と再照会
     * 検証契約: GET 後保存が失敗したら同じ操作 ID で照会を再実行し、境界を閉じる。
     */
    @Test
    void getPostSaveFailureRecoversOwnedBoundaryWithRealCoordinator() {
        Fixture fixture = new Fixture(true);
        fixture.completedLocally();
        when(fixture.repository.find(fixture.accountId))
            .thenReturn(fixture.status(true, true, false, true));
        fixture.failNextPostSave.set(true);

        fixture.service.refresh(fixture.player, false, () -> { });
        assertTrue(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        fixture.service.refresh(fixture.player, false, () -> { });
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertTrue(fixture.service.canClaim(fixture.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: POST 後保存失敗と recovery GET 失敗の間は境界を保持し、次の GET で解決する。
     */
    @Test
    void failedRecoveryGetCanRetryWithRealCoordinator() {
        Fixture fixture = new Fixture(true);
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenReturn(fixture.status(true, true, true, false));
        when(fixture.repository.find(fixture.accountId))
            .thenThrow(new IllegalStateException("recovery GET failed"))
            .thenReturn(fixture.status(true, true, true, false));
        fixture.failNextPostSave.set(true);

        fixture.service.claim(fixture.player, () -> { });
        assertTrue(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertFalse(fixture.service.canClaim(fixture.accountId));
        fixture.service.refresh(fixture.player, false, () -> { });
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertFalse(fixture.service.canClaim(fixture.accountId));
        verify(fixture.messages).send(fixture.player, PlayerMsgId.P_5222);
        verify(fixture.repository, times(3)).find(fixture.accountId);
        verify(fixture.repository, times(1)).claim(fixture.accountId, fixture.userId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### 受取と保存境界
     * 検証契約: 受取中の一覧再表示は同じ結果を待ち、claim callback より前に recovery を予約しない。
     */
    @Test
    void reopeningGuideDuringClaimWaitsForClaimResult() {
        Fixture fixture = new Fixture(true, true);
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenReturn(fixture.status(true, true, true, false));
        when(fixture.repository.find(fixture.accountId))
            .thenThrow(new IllegalStateException("unexpected overlapping GET"));
        AtomicInteger redraws = new AtomicInteger();

        fixture.service.claim(fixture.player, () -> { });
        fixture.service.refresh(fixture.player, false, redraws::incrementAndGet);
        verify(fixture.repository, times(1)).find(fixture.accountId);
        fixture.runNextMainTask();

        assertEquals(1, redraws.get());
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        assertFalse(fixture.service.canClaim(fixture.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 5. ガイド全達成報酬 > ### セッション切替
     * 検証契約: 旧 claim callback は再ログイン後に始まった recovery の操作 ID を消さず、GET 失敗後も再試行できる。
     */
    @Test
    void staleClaimCallbackDoesNotEraseNewSessionRecoveryToken() {
        Fixture fixture = new Fixture(true, true);
        fixture.ready();
        when(fixture.repository.claim(fixture.accountId, fixture.userId))
            .thenReturn(fixture.status(true, true, true, false));
        fixture.service.claim(fixture.player, () -> { });
        fixture.service.discardAccountState(fixture.accountId);
        AstPlayer nextSession = mock(AstPlayer.class);
        when(nextSession.getBukkit()).thenReturn(fixture.player);
        when(nextSession.getAccount()).thenReturn(fixture.account);
        AstPlayerCache.put(nextSession);
        when(fixture.repository.find(fixture.accountId))
            .thenThrow(new IllegalStateException("recovery GET failed"))
            .thenReturn(fixture.status(true, true, true, false));

        fixture.service.refresh(fixture.player, false, () -> { });
        fixture.runNextMainTask(); // Old claim callback.
        fixture.runNextMainTask(); // Failed new-session recovery callback.
        assertTrue(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        fixture.service.refresh(fixture.player, false, () -> { });
        fixture.runNextMainTask();
        assertFalse(fixture.coordinator.hasUnresolvedExternalOperation(fixture.accountId));
        verify(fixture.repository, times(3)).find(fixture.accountId);
    }

    private static final class Fixture {
        private final UUID accountId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final UUID playerId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final Server server = mock(Server.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final UserModel user = mock(UserModel.class);
        private final GuideService guideService = mock(GuideService.class);
        private final InventorySaveCoordinator coordinator;
        private final GuideCompletionRewardRepository repository = mock(GuideCompletionRewardRepository.class);
        private final PlayerMessageService messages = mock(PlayerMessageService.class);
        private final GuideCompletionRewardService service;
        private final List<Pending> pending = new ArrayList<>();
        private final List<Runnable> mainThreadTasks = new ArrayList<>();
        private final boolean realCoordinator;
        private final boolean deferMainThread;
        private final AtomicBoolean failNextPostSave = new AtomicBoolean();

        private Fixture() {
            this(false, false);
        }

        private Fixture(boolean realCoordinator) {
            this(realCoordinator, false);
        }

        private Fixture(boolean realCoordinator, boolean deferMainThread) {
            this.realCoordinator = realCoordinator;
            this.deferMainThread = deferMainThread;
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                if (deferMainThread) mainThreadTasks.add(task);
                else task.run();
                return mock(BukkitTask.class);
            }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.isOnline()).thenReturn(true);
            when(astPlayer.getBukkit()).thenReturn(player);
            when(astPlayer.getAccount()).thenReturn(account);
            when(astPlayer.getUser()).thenReturn(user);
            when(account.getUuid()).thenReturn(accountId);
            when(user.getUuid()).thenReturn(userId);
            AstPlayerCache.put(astPlayer);
            if (realCoordinator) {
                PlayerInventoryState state = new PlayerInventoryState(accountId);
                PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
                registry.put(state);
                InventoryPersistence persistence = mock(InventoryPersistence.class);
                var baseline = new InventoryPersistence.PersistedInventoryBaseline(accountId, Map.of());
                when(persistence.saveNowWithBaseline(state)).thenAnswer(invocation -> {
                    state.takeAndClearDirty();
                    return baseline;
                });
                when(persistence.saveNow(state)).thenAnswer(invocation -> {
                    if (failNextPostSave.compareAndSet(true, false)) throw new IllegalStateException("post-save failed");
                    state.takeAndClearDirty();
                    return true;
                });
                when(persistence.hasPendingChanges(state)).thenAnswer(invocation -> state.isDirty());
                coordinator = new InventorySaveCoordinator(persistence, registry, Runnable::run);
            } else {
                coordinator = mock(InventorySaveCoordinator.class);
                doAnswer(invocation -> pendingOperation(invocation.getArgument(2)))
                    .when(coordinator).executeExclusiveAfterSave(eq(accountId), any(UUID.class), any());
                doAnswer(invocation -> pendingOperation(invocation.getArgument(2)))
                    .when(coordinator).executeExclusiveAfterSaveRecovery(eq(accountId), any(UUID.class), any());
            }
            service = new GuideCompletionRewardService(plugin, guideService, coordinator, repository, messages);
        }

        private CompletableFuture<Object> pendingOperation(
            Function<InventoryPersistence.PersistedInventoryBaseline, ?> operation
        ) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            pending.add(new Pending(operation, result));
            return result;
        }

        private void completedLocally() {
            when(guideService.isProgressLoaded(accountId)).thenReturn(true);
            when(guideService.areAllGuidesCompleted(accountId)).thenReturn(true);
        }

        private GuideCompletionRewardStatus status(boolean configured, boolean allCompleted,
            boolean claimed, boolean canClaim) {
            return new GuideCompletionRewardStatus(accountId, configured, allCompleted, claimed, canClaim,
                claimed ? "guide-completion-mail" : null);
        }

        private void ready() {
            completedLocally();
            when(repository.find(accountId)).thenReturn(status(true, true, false, true));
            service.refresh(player, false, () -> { });
            if (!realCoordinator) completeNext();
            if (deferMainThread) runNextMainTask();
            assertTrue(service.canClaim(accountId));
        }

        private void runNextMainTask() {
            mainThreadTasks.removeFirst().run();
        }

        private void completeNext() {
            Pending operation = pending.removeFirst();
            try {
                operation.result.complete(operation.action.apply(null));
            } catch (Throwable failure) {
                operation.result.completeExceptionally(failure);
            }
        }

        private void failNext() {
            pending.removeFirst().result.completeExceptionally(new IllegalStateException("save failed"));
        }
    }

    private record Pending(
        Function<InventoryPersistence.PersistedInventoryBaseline, ?> action,
        CompletableFuture<Object> result
    ) {
    }
}
