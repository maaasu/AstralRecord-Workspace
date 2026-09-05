package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitItem;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitResult;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
import io.github.maaasu.astralRecord.feature.trade.repository.TradeRepository;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 有効な送信開始は送信者だけを active session に登録し、受信者 GUI を開かない。
     */
    @Test
    void openSendCreatesSenderOnlySessionAndDoesNotOpenRecipientGui() throws Exception {
        TestContext context = new TestContext();
        clearSessions(context.service);
        Runnable returnAction = mock(Runnable.class);
        AstPlayer recipientAstPlayer = astPlayer(context.recipientAccountId);
        try (var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class);
             var names = mockStatic(io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.class)) {
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            cache.when(() -> AstPlayerCache.get(context.recipient)).thenReturn(recipientAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.sender)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.recipient)).thenReturn(true);
            names.when(() -> io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.toPlain(any(AccountModel.class)))
                .thenReturn("sender", "recipient");

            context.service.openSend(context.sender, context.recipient, returnAction);
        }

        TradeSession session = context.service.getOpenSession(context.senderId);
        assertEquals(context.senderId, session.getPlayerAUuid());
        assertEquals(context.recipientId, session.getPlayerBUuid());
        assertSame(returnAction, session.getReturnAction());
        assertNull(context.service.getOpenSession(context.recipientId));
        verify(context.tradeGui).open(eq(context.sender), eq(session), any(Runnable.class), any(Runnable.class));
        verify(context.tradeGui, never()).open(eq(context.recipient), any(TradeSession.class), any(Runnable.class), any(Runnable.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: back 指定の leave は未確定送信を中止して、送信者が online の場合だけ開始元 action を一度実行する。
     */
    @Test
    void leaveWithBackCancelsOnlySendersDraftAndRunsReturnAction() throws Exception {
        TestContext context = new TestContext();
        Runnable returnAction = mock(Runnable.class);
        context.session.setReturnAction(returnAction);
        try (var bukkit = mockStatic(Bukkit.class); var cache = mockStatic(AstPlayerCache.class)) {
            bukkit.when(() -> Bukkit.getPlayer(context.senderId)).thenReturn(context.sender);
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            context.service.leave(context.sender, true);
        }
        assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
        assertNull(context.service.getOpenSession(context.senderId));
        verify(returnAction).run();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 初回送信画面の遅延表示中に受信者が離脱した場合、送信者の保留表示だけを取消して孤立 GUI を開かない。
     */
    @Test
    void recipientDepartureCancelsPendingInitialSendGui() throws Exception {
        TestContext context = new TestContext();
        clearSessions(context.service);
        try (var bukkit = mockStatic(Bukkit.class); var cache = mockStatic(AstPlayerCache.class);
             var guard = mockStatic(AccountModeGuard.class); var names = mockStatic(
                 io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.class);
             var guiOpen = mockStatic(GuiOpenSupport.class)) {
            bukkit.when(() -> Bukkit.getPlayer(context.senderId)).thenReturn(context.sender);
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            AstPlayer recipientAst = astPlayer(context.recipientAccountId);
            cache.when(() -> AstPlayerCache.get(context.recipient)).thenReturn(recipientAst);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.sender)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.recipient)).thenReturn(true);
            names.when(() -> io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.toPlain(any(AccountModel.class)))
                .thenReturn("sender", "recipient");

            context.service.openSend(context.sender, context.recipient, null);
            context.service.cancelRelatedSessions(context.recipient);

            guiOpen.verify(() -> GuiOpenSupport.cancelPending(context.senderId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: item と金額がともに空の送信は API transaction を開始せず、送信者へ不足を通知する。
     */
    @Test
    void emptySendDoesNotStartCommit() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class)) {
            context.stubParticipants(bukkit, cache, guard);
            context.session.setItems(context.senderId, List.of(), List.of());
            context.session.setGoldAmount(context.senderId, 0L);

            invokeComplete(context.service, context.session);

            assertEquals(TradeSessionStatus.OPEN, context.session.getStatus());
            verify(context.repository, never()).commit(any());
            verify(context.messageService).send(context.sender, PlayerMsgId.P_6202);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: 開始時 account と現在 account が異なる送信者は、元 account の送信を API へ確定できない。
     */
    @Test
    void changedSenderAccountCannotCommitOriginalDraft() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class)) {
            context.stubParticipants(bukkit, cache, guard);
            AstPlayer switchedAst = astPlayer(UUID.randomUUID());
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(switchedAst);

            invokeComplete(context.service, context.session);

            assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
            verify(context.repository, never()).commit(any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## アイテム予約
     * 検証契約: 追加予約の表示更新に失敗しても、既存 escrow の source entry ID と数量を維持する。
     */
    @Test
    void failedOfferPreservesExistingSourceEntryIds() throws Exception {
        TestContext context = new TestContext();
        context.session.setItems(context.senderId, List.of(itemStack(2)), List.of(context.sourceEntryId));
        doThrow(new IllegalStateException("render failed")).when(context.inventoryService)
            .hideOwnedEntryQuantityFromGui(any(), any(), anyInt());
        try (var cache = mockStatic(AstPlayerCache.class);
             var logger = mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            assertFalse(context.service.offerOwnedItem(context.sender, 9, ClickType.LEFT, itemStack(4)));
        }
        assertEquals(List.of(new TradeCommitItem(context.sourceEntryId, 2L)),
            context.session.getCommitItems(context.senderId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## アイテム予約
     * 検証契約: 予約解除の表示更新に失敗しても、escrow の source entry ID と数量を維持する。
     */
    @Test
    void failedWithdrawalPreservesExistingSourceEntryIds() throws Exception {
        TestContext context = new TestContext();
        context.session.setItems(context.senderId, List.of(itemStack(2)), List.of(context.sourceEntryId));
        doThrow(new IllegalStateException("restore failed")).when(context.inventoryService)
            .restoreHiddenEntryQuantityToGui(context.senderAstPlayer, context.sourceEntryId, 1);
        try (var cache = mockStatic(AstPlayerCache.class);
             var logger = mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            assertFalse(context.service.withdrawOfferedItem(context.sender, 0, ClickType.LEFT));
        }
        assertEquals(List.of(new TradeCommitItem(context.sourceEntryId, 2L)),
            context.session.getCommitItems(context.senderId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## アイテム予約
     * 検証契約: 同じ source entry を複数の表示 stack に分割しても、API 明細は一件に集約して合計数量を送る。
     */
    @Test
    void splitEscrowItemsKeepSourceEntryIdAndAggregateForApi() throws Exception {
        TestContext context = new TestContext();
        ItemStack stack = splitItemStack(70, 64);
        try (var cache = mockStatic(AstPlayerCache.class); var bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.sender)).thenReturn(context.senderAstPlayer);
            assertTrue(context.service.offerOwnedItem(context.sender, 9, ClickType.SHIFT_RIGHT, stack));
        }
        assertEquals(List.of(new TradeCommitItem(context.sourceEntryId, 70L)),
            context.session.getCommitItems(context.senderId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: 送信確定 API は sender の item と金額だけを送り、recipient 側の item は空・金額は 0 とする。
     */
    @Test
    void commitRequestKeepsRecipientSideEmptyAndZero() throws Exception {
        try (CommitContext context = new CommitContext()) {
            UUID sourceEntryId = UUID.randomUUID();
            context.session.setItems(context.senderId, List.of(itemStack(3)), List.of(sourceEntryId));
            context.session.setGoldAmount(context.senderId, 120L);

            invokeCommit(context.service, context.session).join();

            ArgumentCaptor<TradeCommitRequest> request = ArgumentCaptor.forClass(TradeCommitRequest.class);
            verify(context.repository).commit(request.capture());
            assertEquals(List.of(new TradeCommitItem(sourceEntryId, 3L)), request.getValue().playerAItems());
            assertTrue(request.getValue().playerBItems().isEmpty());
            assertEquals(120L, request.getValue().playerAGold());
            assertEquals(0L, request.getValue().playerBGold());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: 確定開始後の同じ送信者の再送信は、新しい API transaction を作成しない。
     */
    @Test
    void duplicateSendWhileCommittingDoesNotCreateSecondApiRequest() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            context.stubParticipants(bukkit, cache, guard);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            registerSession(context.service, context.session);

            context.service.send(context.sender);
            context.service.send(context.sender);

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(context.repository, times(1)).commit(any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 再同期・回復
     * 検証契約: API 応答喪失は COMMITTING と未解決境界を維持し、cancel で元の予約へ戻さない。
     */
    @Test
    void responseLossKeepsCommittingAndRejectsCancellation() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(context.repository.commit(any())).thenThrow(new IllegalStateException("response lost"));

            assertThrows(CompletionException.class, () -> invokeCommit(context.service, context.session).join());
            invokeFinish(context.service, context.session, new IllegalStateException("response lost"));

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            assertTrue(context.coordinator.hasUnresolvedExternalOperation(context.senderAccountId));
            assertTrue(context.coordinator.hasUnresolvedExternalOperation(context.recipientAccountId));
            context.service.cancelTrade(context.session);
            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(scheduler).runTaskLaterAsynchronously(eq(context.plugin), any(Runnable.class), eq(20L));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 再同期・回復
     * 検証契約: 明示的な 4xx 業務拒否だけは未解決境界を解除し、送信 draft を OPEN へ戻す。
     */
    @Test
    void explicitApiRejectionReopensDraftAndReleasesPreparedBoundaries() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class);
             var logger = mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            context.stubParticipants(bukkit, cache, guard);
            when(context.repository.commit(any())).thenThrow(
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));

            assertThrows(CompletionException.class, () -> invokeCommit(context.service, context.session).join());
            invokeFinish(context.service, context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));

            assertEquals(TradeSessionStatus.OPEN, context.session.getStatus());
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.senderAccountId));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.recipientAccountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 再同期・回復
     * 検証契約: API 成功後に受信者の再同期が失敗した場合、同じ operation ID を replay し、未完了の受信者だけを復旧する。
     */
    @Test
    void recoveryReplaysSameOperationForOnlyUnfinishedRecipient() throws Exception {
        try (CommitContext context = new CommitContext(true); var bukkit = mockStatic(Bukkit.class);
             var logger = mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            org.mockito.Mockito.doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return null;
            }).when(scheduler).runTaskLaterAsynchronously(eq(context.plugin), any(Runnable.class), eq(20L));
            org.mockito.Mockito.doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return null;
            }).when(scheduler).runTask(eq(context.plugin), any(Runnable.class));

            assertThrows(CompletionException.class, () -> invokeCommit(context.service, context.session).join());
            invokeFinish(context.service, context.session, new IllegalStateException("recipient reconciliation failed"));

            ArgumentCaptor<TradeCommitRequest> requests = ArgumentCaptor.forClass(TradeCommitRequest.class);
            verify(context.repository, times(2)).commit(requests.capture());
            assertEquals(context.session.getSessionId(), requests.getAllValues().get(0).operationId());
            assertEquals(context.session.getSessionId(), requests.getAllValues().get(1).operationId());
            assertEquals(TradeSessionStatus.COMPLETED, context.session.getStatus());
            verify(context.inventoryService, times(1)).reconcileTradeInventoryEntries(
                eq(context.senderAccountId), eq(context.result.playerAAffectedInventoryEntryIds()), any());
            verify(context.inventoryService, times(2)).reconcileTradeInventoryEntries(
                eq(context.recipientAccountId), eq(context.result.playerBAffectedInventoryEntryIds()), any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 再同期・回復
     * 検証契約: 両accountが同じsingle-thread executorを利用しても相互のfuture待機で停止せず確定できる。
     */
    @Test
    void commitCompletesWhenBothAccountsShareOneWorker() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (CommitContext context = new CommitContext(false, executor)) {
            assertEquals(context.result,
                invokeCommit(context.service, context.session).get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.senderAccountId));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.recipientAccountId));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: 受信者 BAG が満杯でも通常容量で事前拒否せず、API transaction と再同期を開始する。
     */
    @Test
    void fullRecipientBagDoesNotPreventSendCommitFromStarting() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            context.stubParticipants(bukkit, cache, guard);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ItemModel itemModel = mock(ItemModel.class);
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(context.itemReferenceResolver.resolveItemModel(any(ItemStack.class))).thenReturn(itemModel);

            invokeComplete(context.service, context.session);

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(context.repository).commit(any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 受信通知
     * 検証契約: 確定後は送信者へ実送金額と宛先を、受信者へ送信者・金額・各 item 表示名と数量を通知する。
     */
    @Test
    void completedSendNotifiesSenderAndRecipientWithActualContents() throws Exception {
        try (CommitContext context = new CommitContext(); var bukkit = mockStatic(Bukkit.class);
             var cache = mockStatic(AstPlayerCache.class); var guard = mockStatic(AccountModeGuard.class)) {
            context.stubParticipants(bukkit, cache, guard);
            context.session.setGoldAmount(context.senderId, 45L);
            ItemModel itemModel = mock(ItemModel.class);
            when(itemModel.getName()).thenReturn("テスト鉱石");
            when(context.itemReferenceResolver.resolveItemModel(any(ItemStack.class))).thenReturn(itemModel);

            invokeFinish(context.service, context.session, null);

            verify(context.messageService).send(context.sender, PlayerMsgId.P_6207, 45L, "recipient");
            verify(context.messageService).send(context.recipient, PlayerMsgId.P_6201, "sender", 45L);
            verify(context.messageService).send(context.recipient, PlayerMsgId.P_6211, "テスト鉱石", 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<TradeCommitResult> invokeCommit(TradeService service, TradeSession session) throws Exception {
        Method method = TradeService.class.getDeclaredMethod("commitTradeWithInventoryLocks", TradeSession.class);
        method.setAccessible(true);
        return (CompletableFuture<TradeCommitResult>) method.invoke(service, session);
    }

    private static void invokeComplete(TradeService service, TradeSession session) throws Exception {
        Method method = TradeService.class.getDeclaredMethod("completeTrade", TradeSession.class);
        method.setAccessible(true);
        method.invoke(service, session);
    }

    private static void invokeFinish(TradeService service, TradeSession session, Throwable failure) throws Exception {
        Method method = TradeService.class.getDeclaredMethod("finishTradeCommit", TradeSession.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(service, session, failure);
    }

    @SuppressWarnings("unchecked")
    private static void registerSession(TradeService service, TradeSession session) throws Exception {
        Field sessions = TradeService.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        ((Map<UUID, TradeSession>) sessions.get(service)).put(session.getSessionId(), session);
        Field active = TradeService.class.getDeclaredField("activeSessionByPlayer");
        active.setAccessible(true);
        ((Map<UUID, UUID>) active.get(service)).put(session.getPlayerAUuid(), session.getSessionId());
    }

    @SuppressWarnings("unchecked")
    private static void clearSessions(TradeService service) throws Exception {
        Field sessions = TradeService.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        ((Map<UUID, TradeSession>) sessions.get(service)).clear();
        Field active = TradeService.class.getDeclaredField("activeSessionByPlayer");
        active.setAccessible(true);
        ((Map<UUID, UUID>) active.get(service)).clear();
    }

    private static AstPlayer astPlayer(UUID accountId) {
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        return player;
    }

    private static ItemStack itemStack(int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private static ItemStack splitItemStack(int amount, int maxStackSize) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger currentAmount = new AtomicInteger(amount);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.getAmount()).thenAnswer(ignored -> currentAmount.get());
        when(item.getMaxStackSize()).thenReturn(maxStackSize);
        org.mockito.Mockito.doAnswer(invocation -> {
            currentAmount.set(invocation.getArgument(0));
            return null;
        }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(ignored -> splitItemStack(currentAmount.get(), maxStackSize));
        return item;
    }

    private static WorldMasterData baseWorld() {
        return new WorldMasterData(1, "base", "base", WorldType.BASE, "", "", false, false, 0,
            false, false, false, false, WorldSpawnLocation.defaultLocation(), "", null, null, null);
    }

    private static final class TestContext {
        private final UUID senderId = UUID.randomUUID();
        private final UUID recipientId = UUID.randomUUID();
        private final UUID senderAccountId = UUID.randomUUID();
        private final UUID recipientAccountId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final CurrencyService currencyService = mock(CurrencyService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final ItemReferenceResolver itemReferenceResolver = mock(ItemReferenceResolver.class);
        private final ItemModel itemModel = mock(ItemModel.class);
        private final InventoryEntryModel sourceEntry = mock(InventoryEntryModel.class);
        private final UUID sourceEntryId = UUID.randomUUID();
        private final Player sender = mock(Player.class);
        private final Player recipient = mock(Player.class);
        private final AstPlayer senderAstPlayer = astPlayer(senderAccountId);
        private final WorldService worldService = mock(WorldService.class);
        private final World senderWorld = mock(World.class);
        private final World recipientWorld = mock(World.class);
        private final TradeSession session = new TradeSession(UUID.randomUUID(), senderId, senderAccountId, "sender",
            recipientId, recipientAccountId, "recipient", Instant.now());
        private final TradeService service = new TradeService(plugin, tradeGui, goldAmountSettingGui, inventoryService,
            currencyService, messageService, itemReferenceResolver);

        private TestContext() throws Exception {
            for (Player player : List.of(sender, recipient)) {
                InventoryView view = mock(InventoryView.class);
                Inventory inventory = mock(Inventory.class);
                when(view.getTopInventory()).thenReturn(inventory);
                when(player.getOpenInventory()).thenReturn(view);
                when(player.isOnline()).thenReturn(true);
            }
            when(sender.getUniqueId()).thenReturn(senderId);
            when(sender.getWorld()).thenReturn(senderWorld);
            when(recipient.getUniqueId()).thenReturn(recipientId);
            when(recipient.getWorld()).thenReturn(recipientWorld);
            when(plugin.getWorldService()).thenReturn(worldService);
            when(worldService.findByBukkitWorld(senderWorld)).thenReturn(baseWorld());
            when(worldService.findByBukkitWorld(recipientWorld)).thenReturn(baseWorld());
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(inventoryService.getOwnedItemModelAtBukkitSlot(senderAstPlayer, 9)).thenReturn(itemModel);
            when(inventoryService.getOwnedEntryAtBukkitSlot(senderAstPlayer, 9)).thenReturn(sourceEntry);
            when(sourceEntry.getInventoryEntryId()).thenReturn(sourceEntryId);
            registerSession(service, session);
        }
    }

    private static final class CommitContext implements AutoCloseable {
        private final UUID senderId = UUID.randomUUID();
        private final UUID recipientId = UUID.randomUUID();
        private final UUID senderAccountId = UUID.randomUUID();
        private final UUID recipientAccountId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final CurrencyService currencyService = mock(CurrencyService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final ItemReferenceResolver itemReferenceResolver = mock(ItemReferenceResolver.class);
        private final Player sender = mock(Player.class);
        private final Player recipient = mock(Player.class);
        private final WorldService worldService = mock(WorldService.class);
        private final World senderWorld = mock(World.class);
        private final World recipientWorld = mock(World.class);
        private final TradeRepository repository = mock(TradeRepository.class);
        private final PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        private final InventoryPersistence persistence = mock(InventoryPersistence.class);
        private final InventorySaveCoordinator coordinator;
        private final TradeSession session = new TradeSession(UUID.randomUUID(), senderId, senderAccountId, "sender",
            recipientId, recipientAccountId, "recipient", Instant.now());
        private final TradeCommitResult result = new TradeCommitResult(session.getSessionId(), List.of(UUID.randomUUID()),
            List.of(UUID.randomUUID()), Instant.now());
        private final TradeService service;

        private CommitContext() {
            this(false);
        }

        private CommitContext(boolean failRecipientReconciliation) {
            this(failRecipientReconciliation, Runnable::run);
        }

        private CommitContext(boolean failRecipientReconciliation, Executor executor) {
            coordinator = new InventorySaveCoordinator(persistence, stateRegistry, executor);
            PlayerInventoryState senderState = new PlayerInventoryState(senderAccountId);
            PlayerInventoryState recipientState = new PlayerInventoryState(recipientAccountId);
            stateRegistry.put(senderState);
            stateRegistry.put(recipientState);
            when(persistence.saveNowWithBaseline(senderState)).thenReturn(
                new InventoryPersistence.PersistedInventoryBaseline(senderAccountId, Map.of()));
            when(persistence.saveNowWithBaseline(recipientState)).thenReturn(
                new InventoryPersistence.PersistedInventoryBaseline(recipientAccountId, Map.of()));
            when(persistence.saveNow(senderState)).thenReturn(true);
            when(persistence.saveNow(recipientState)).thenReturn(true);
            when(persistence.hasPendingChanges(senderState)).thenReturn(false);
            when(persistence.hasPendingChanges(recipientState)).thenReturn(false);
            when(repository.commit(any())).thenReturn(result);
            ItemModel transferableItem = mock(ItemModel.class);
            when(itemReferenceResolver.resolveItemModel(any(ItemStack.class))).thenReturn(transferableItem);
            if (failRecipientReconciliation) {
                doThrow(new IllegalStateException("recipient reconciliation failed")).doNothing()
                    .when(inventoryService).reconcileTradeInventoryEntries(
                        eq(recipientAccountId), eq(result.playerBAffectedInventoryEntryIds()), any());
            }
            for (Player player : List.of(sender, recipient)) {
                InventoryView view = mock(InventoryView.class);
                Inventory inventory = mock(Inventory.class);
                when(view.getTopInventory()).thenReturn(inventory);
                when(player.getOpenInventory()).thenReturn(view);
                when(player.isOnline()).thenReturn(true);
            }
            when(sender.getUniqueId()).thenReturn(senderId);
            when(sender.getWorld()).thenReturn(senderWorld);
            when(recipient.getUniqueId()).thenReturn(recipientId);
            when(recipient.getWorld()).thenReturn(recipientWorld);
            when(plugin.getWorldService()).thenReturn(worldService);
            when(worldService.findByBukkitWorld(senderWorld)).thenReturn(baseWorld());
            when(worldService.findByBukkitWorld(recipientWorld)).thenReturn(baseWorld());
            session.setItems(senderId, List.of(itemStack(1)), List.of(UUID.randomUUID()));
            service = new TradeService(plugin, tradeGui, goldAmountSettingGui, inventoryService, currencyService,
                messageService, itemReferenceResolver, coordinator, repository);
        }

        private void stubParticipants(org.mockito.MockedStatic<Bukkit> bukkit,
                                      org.mockito.MockedStatic<AstPlayerCache> cache,
                                      org.mockito.MockedStatic<AccountModeGuard> guard) {
            for (Player player : List.of(sender, recipient)) {
                UUID accountId = player == sender ? senderAccountId : recipientAccountId;
                UUID playerId = player == sender ? senderId : recipientId;
                TradeGui.TradeHolder holder = new TradeGui.TradeHolder(session.getSessionId(), playerId);
                when(player.getOpenInventory().getTopInventory().getHolder()).thenReturn(holder);
                bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
                AstPlayer ast = astPlayer(accountId);
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(ast);
                guard.when(() -> AccountModeGuard.isGameplayPlayer(player)).thenReturn(true);
            }
        }

        @Override
        public void close() {
        }
    }
}
