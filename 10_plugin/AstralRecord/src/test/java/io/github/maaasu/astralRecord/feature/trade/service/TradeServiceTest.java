package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequestStatus;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitItem;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitResult;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
import io.github.maaasu.astralRecord.feature.trade.repository.TradeRepository;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Item 提示・取り下げ
     * 検証契約: 表示itemを正本にせず所有 entry を特定し、提示中は正本を減算せず表示予約だけを追加する。
     */
    @Test
    void offerReservesAuthoritativeOwnedEntryBeforeAddingEscrow() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed);
        }

        assertTrue(offered);
        assertEquals(1, context.session.getItems(context.playerId).size());
        assertEquals(4, context.session.getItems(context.playerId).getFirst().getAmount());
        assertEquals(context.sourceEntryId, context.session.getCommitItems(context.playerId).getFirst().sourceInventoryEntryId());
        verify(context.inventoryService).getOwnedItemModelAtBukkitSlot(context.astPlayer, 9);
        verify(context.inventoryService).getOwnedEntryAtBukkitSlot(context.astPlayer, 9);
        verify(context.inventoryService).hideOwnedEntryQuantityFromGui(context.astPlayer, context.sourceEntryId, 4);
        verify(context.inventoryService, never()).takeOwnedItemAmount(context.astPlayer, 9, 4);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Item 提示・取り下げ
     * 検証契約: 所有 entry を特定できなければ escrow と表示予約を変更しない。
     */
    @Test
    void missingOwnedEntryFailsClosedWithoutReservingInventory() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);
        when(context.inventoryService.getOwnedEntryAtBukkitSlot(context.astPlayer, 9)).thenReturn(null);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed);
        }

        assertFalse(offered);
        assertTrue(context.session.getItems(context.playerId).isEmpty());
        verify(context.inventoryService, never()).hideOwnedEntryQuantityFromGui(any(), any(), anyInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## GUI event
     * 検証契約: item提示で相手が操作中のGold金額設定GUIをTrade GUIへ強制遷移させない。
     */
    @Test
    void offeringItemRefreshesOpenTradeViewsWithoutReopeningPartnerGoldAmountSetting() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            bukkit.when(() -> Bukkit.getPlayer(context.partnerId)).thenReturn(context.partner);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed);
        }

        assertTrue(offered);
        verify(context.tradeGui).refreshIfOpen(context.player, context.session);
        verify(context.tradeGui).refreshIfOpen(context.partner, context.session);
        verify(context.tradeGui, never()).open(any(Player.class), any(TradeSession.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 招待・承認
     * 検証契約: 期限切れの申請は EXPIRED に遷移後、pending 申請だけを保持する request 管理から除去する。
     */
    @Test
    void expiringRequestsRemovesTerminalRequestsAndKeepsPendingRequests() throws Exception {
        TestContext context = new TestContext();
        Instant now = Instant.now();
        TradeRequest expired = new TradeRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "expired-sender",
            UUID.randomUUID(),
            "expired-target",
            now.minusSeconds(61),
            now.minusSeconds(1)
        );
        TradeRequest pending = new TradeRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "pending-sender",
            UUID.randomUUID(),
            "pending-target",
            now,
            now.plusSeconds(60)
        );
        TestContext.registerRequest(context.service, expired);
        TestContext.registerRequest(context.service, pending);

        TestContext.invokeExpireRequests(context.service);

        assertEquals(TradeRequestStatus.EXPIRED, expired.getStatus());
        assertFalse(TestContext.requestsOf(context.service).containsKey(expired.getRequestId()));
        assertSame(pending, TestContext.requestsOf(context.service).get(pending.getRequestId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 処理要点
     * 検証契約: 二 account の事前保存を single-thread executor で実行しても、lane 間の同期待機で停止せず、
     * API 確定後に両 account を再同期・保存する。
     */
    @Test
    void commitDoesNotDeadlockWhenBothAccountLanesShareSingleThreadExecutor() throws Exception {
        try (CommitContext context = new CommitContext(false)) {
            TradeCommitResult completed = invokeCommit(context.service, context.session)
                .get(2, TimeUnit.SECONDS);

            assertEquals(context.result, completed);
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerAAccountId));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerBAccountId));
            verify(context.repository).commit(any());
            verify(context.inventoryService).reconcileExternalInventoryEntries(
                eq(context.playerAAccountId),
                eq(context.result.playerAAffectedInventoryEntryIds()),
                any()
            );
            verify(context.inventoryService).reconcileExternalInventoryEntries(
                eq(context.playerBAccountId),
                eq(context.result.playerBAffectedInventoryEntryIds()),
                any()
            );
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: API 成功後の local 再同期失敗は OPEN/cancel へ戻さず COMMITTING と未解決境界を維持し、
     * 同じ operation ID の再同期 replay を予約する。
     */
    @Test
    void postApiReconciliationFailureKeepsCommittingAndRejectsCancellation() throws Exception {
        try (CommitContext context = new CommitContext(true);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger = mockStatic(
                 io.github.maaasu.astralRecord.infrastructure.logging.Logger.class
             )) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            CompletableFuture<TradeCommitResult> commit = invokeCommit(context.service, context.session);
            assertThrows(CompletionException.class, commit::join);

            invokeFinish(context.service, context.session, new IllegalStateException("local reconciliation failed"));

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            assertTrue(context.coordinator.hasUnresolvedExternalOperation(context.playerBAccountId));
            context.service.cancelTrade(context.session);
            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(scheduler).runTaskLaterAsynchronously(eq(context.plugin), any(Runnable.class), eq(20L));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: API 確定済みの再同期失敗は同じ operation ID を API へ replay し、未完了 account の
     * 再同期・保存が成功した時だけトレードを完了する。
     */
    @Test
    void recoveryReplaysSameOperationIdBeforeCompletingUnfinishedAccount() throws Exception {
        try (CommitContext context = new CommitContext(true, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger = mockStatic(
                 io.github.maaasu.astralRecord.infrastructure.logging.Logger.class
             )) {
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
            invokeFinish(context.service, context.session, new IllegalStateException("local reconciliation failed"));

            ArgumentCaptor<TradeCommitRequest> requests = ArgumentCaptor.forClass(TradeCommitRequest.class);
            verify(context.repository, times(2)).commit(requests.capture());
            assertEquals(context.session.getSessionId(), requests.getAllValues().get(0).operationId());
            assertEquals(context.session.getSessionId(), requests.getAllValues().get(1).operationId());
            assertEquals(TradeSessionStatus.COMPLETED, context.session.getStatus());
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerAAccountId));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerBAccountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: 送信後の応答喪失は API transaction の未確定を証明しないため、同じ operation ID を
     * replay するまで COMMITTING・未解決境界を維持し cancel を拒否する。
     */
    @Test
    void responseLossAfterCommitRequestKeepsTradeCommittingForReplay() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger = mockStatic(
                 io.github.maaasu.astralRecord.infrastructure.logging.Logger.class
             )) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(context.repository.commit(any())).thenThrow(new IllegalStateException("response lost"));

            assertThrows(CompletionException.class, () -> invokeCommit(context.service, context.session).join());
            invokeFinish(context.service, context.session, new IllegalStateException("response lost"));

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            assertTrue(context.coordinator.hasUnresolvedExternalOperation(context.playerAAccountId));
            assertTrue(context.coordinator.hasUnresolvedExternalOperation(context.playerBAccountId));
            context.service.cancelTrade(context.session);
            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(scheduler).runTaskLaterAsynchronously(eq(context.plugin), any(Runnable.class), eq(20L));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: API が明示した 4xx の業務拒否だけは未確定境界を解除して OPEN へ戻す。
     */
    @Test
    void explicitApiRejectionReopensTradeAndReleasesPreparedBoundaries() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger = mockStatic(
                 io.github.maaasu.astralRecord.infrastructure.logging.Logger.class
             )) {
            when(context.repository.commit(any())).thenThrow(
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing")
            );

            assertThrows(CompletionException.class, () -> invokeCommit(context.service, context.session).join());
            invokeFinish(
                context.service,
                context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing")
            );

            assertEquals(TradeSessionStatus.OPEN, context.session.getStatus());
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerAAccountId));
            assertFalse(context.coordinator.hasUnresolvedExternalOperation(context.playerBAccountId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_0-概要.md
     * 章・見出し: # 22_0-概要 > ## 責務
     * 検証契約: 受取 capacity は部分送付・同一 source の複数提示を合算して先に差し引き、
     * その後に相手 item を既存 stack へ順に仮追加する。
     */
    @Test
    void capacitySimulationSubtractsAggregatedOutgoingReservationsBeforeIncomingStackMerge() throws Exception {
        TestContext context = new TestContext();
        UUID sourceEntryId = UUID.randomUUID();
        ItemStack firstIncoming = itemStack(1);
        ItemStack secondIncoming = itemStack(1);
        when(context.itemReferenceResolver.resolveItemModel(firstIncoming)).thenReturn(context.itemModel);
        when(context.itemReferenceResolver.resolveItemModel(secondIncoming)).thenReturn(context.itemModel);
        when(context.inventoryService.removeOwnedEntryAmountsForCapacityCheck(eq(context.astPlayer), any())).thenReturn(true);
        when(context.inventoryService.canAddItemToNormalInventory(context.astPlayer, context.itemModel, 1)).thenReturn(true);
        when(context.inventoryService.returnItemToOwnedInventory(eq(context.astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        when(context.inventoryService.restoreState(context.snapshot)).thenReturn(true);

        boolean receivable;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            receivable = invokeCanReceive(
                context.service,
                context.playerId,
                List.of(firstIncoming, secondIncoming),
                List.of(new TradeCommitItem(sourceEntryId, 2L), new TradeCommitItem(sourceEntryId, 3L))
            );
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Long>> reservations = ArgumentCaptor.forClass(Map.class);
        assertTrue(receivable);
        verify(context.inventoryService).removeOwnedEntryAmountsForCapacityCheck(
            eq(context.astPlayer),
            reservations.capture()
        );
        assertEquals(5L, reservations.getValue().get(sourceEntryId));
        verify(context.inventoryService, times(2)).returnItemToOwnedInventory(
            eq(context.astPlayer),
            any(ItemStack.class)
        );
        verify(context.inventoryService).restoreState(context.snapshot);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<TradeCommitResult> invokeCommit(
        TradeService service,
        TradeSession session
    ) throws Exception {
        Method commit = TradeService.class.getDeclaredMethod("commitTradeWithInventoryLocks", TradeSession.class);
        commit.setAccessible(true);
        return (CompletableFuture<TradeCommitResult>) commit.invoke(service, session);
    }

    private static void invokeFinish(
        TradeService service,
        TradeSession session,
        Throwable failure
    ) throws Exception {
        Method finish = TradeService.class.getDeclaredMethod("finishTradeCommit", TradeSession.class, Throwable.class);
        finish.setAccessible(true);
        finish.invoke(service, session, failure);
    }

    private static boolean invokeCanReceive(
        TradeService service,
        UUID playerUuid,
        List<ItemStack> incomingItems,
        List<TradeCommitItem> outgoingItems
    ) throws Exception {
        Method canReceive = TradeService.class.getDeclaredMethod(
            "canReceiveItems",
            UUID.class,
            List.class,
            List.class
        );
        canReceive.setAccessible(true);
        return (boolean) canReceive.invoke(service, playerUuid, incomingItems, outgoingItems);
    }

    private static final class TestContext {
        private final UUID playerId = UUID.randomUUID();
        private final UUID partnerId = UUID.randomUUID();
        private final UUID accountId = UUID.randomUUID();
        private final UUID partnerAccountId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final TradeCancelConfirmGui cancelConfirmGui = mock(TradeCancelConfirmGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final CurrencyService currencyService = mock(CurrencyService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final ItemReferenceResolver itemReferenceResolver = mock(ItemReferenceResolver.class);
        private final Player player = mock(Player.class);
        private final Player partner = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel itemModel = mock(ItemModel.class);
        private final InventoryEntryModel sourceEntry = mock(InventoryEntryModel.class);
        private final UUID sourceEntryId = UUID.randomUUID();
        private final InventoryService.InventoryStateSnapshot snapshot =
            new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);
        private final TradeSession session = new TradeSession(
            UUID.randomUUID(),
            playerId,
            accountId,
            "tester",
            partnerId,
            partnerAccountId,
            "partner",
            Instant.now()
        );
        private final TradeService service = new TradeService(
            plugin,
            tradeGui,
            cancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            messageService,
            itemReferenceResolver
        );

        private TestContext() throws Exception {
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.isOnline()).thenReturn(true);
            when(partner.getUniqueId()).thenReturn(partnerId);
            when(partner.isOnline()).thenReturn(true);
            when(astPlayer.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 9)).thenReturn(itemModel);
            when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 9)).thenReturn(sourceEntry);
            when(sourceEntry.getInventoryEntryId()).thenReturn(sourceEntryId);
            when(inventoryService.snapshotState(accountId)).thenReturn(snapshot);
            registerSession(service, session);
        }

        @SuppressWarnings("unchecked")
        private static void registerSession(TradeService service, TradeSession session) throws Exception {
            Field sessionsField = TradeService.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ((Map<UUID, TradeSession>) sessionsField.get(service)).put(session.getSessionId(), session);
            Field activeField = TradeService.class.getDeclaredField("activeSessionByPlayer");
            activeField.setAccessible(true);
            ((Map<UUID, UUID>) activeField.get(service)).put(
                session.getPlayerAUuid(),
                session.getSessionId()
            );
        }

        @SuppressWarnings("unchecked")
        private static void registerRequest(TradeService service, TradeRequest request) throws Exception {
            requestsOf(service).put(request.getRequestId(), request);
        }

        @SuppressWarnings("unchecked")
        private static Map<UUID, TradeRequest> requestsOf(TradeService service) throws Exception {
            Field requestsField = TradeService.class.getDeclaredField("requests");
            requestsField.setAccessible(true);
            return (Map<UUID, TradeRequest>) requestsField.get(service);
        }

        private static void invokeExpireRequests(TradeService service) throws Exception {
            var expireRequests = TradeService.class.getDeclaredMethod("expireRequests");
            expireRequests.setAccessible(true);
            expireRequests.invoke(service);
        }
    }

    private static final class CommitContext implements AutoCloseable {
        private final UUID playerAId = UUID.randomUUID();
        private final UUID playerBId = UUID.randomUUID();
        private final UUID playerAAccountId = UUID.randomUUID();
        private final UUID playerBAccountId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final TradeCancelConfirmGui cancelConfirmGui = mock(TradeCancelConfirmGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final CurrencyService currencyService = mock(CurrencyService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final ItemReferenceResolver itemReferenceResolver = mock(ItemReferenceResolver.class);
        private final TradeRepository repository = mock(TradeRepository.class);
        private final PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        private final PlayerInventoryState playerAState = new PlayerInventoryState(playerAAccountId);
        private final PlayerInventoryState playerBState = new PlayerInventoryState(playerBAccountId);
        private final InventoryPersistence persistence = mock(InventoryPersistence.class);
        private final Executor executor;
        private final ExecutorService executorService;
        private final InventorySaveCoordinator coordinator;
        private final TradeSession session = new TradeSession(
            UUID.randomUUID(),
            playerAId,
            playerAAccountId,
            "player-a",
            playerBId,
            playerBAccountId,
            "player-b",
            Instant.now()
        );
        private final TradeCommitResult result = new TradeCommitResult(
            session.getSessionId(),
            List.of(UUID.randomUUID()),
            List.of(UUID.randomUUID()),
            Instant.now()
        );
        private final TradeService service;

        private CommitContext(boolean failPlayerBReconciliation) {
            this(failPlayerBReconciliation, true);
        }

        private CommitContext(boolean failPlayerBReconciliation, boolean useSingleThreadExecutor) {
            executorService = useSingleThreadExecutor ? Executors.newSingleThreadExecutor() : null;
            executor = executorService == null ? Runnable::run : executorService;
            coordinator = new InventorySaveCoordinator(persistence, stateRegistry, executor);
            stateRegistry.put(playerAState);
            stateRegistry.put(playerBState);
            when(persistence.saveNowWithBaseline(playerAState)).thenReturn(
                new InventoryPersistence.PersistedInventoryBaseline(playerAAccountId, Map.of())
            );
            when(persistence.saveNowWithBaseline(playerBState)).thenReturn(
                new InventoryPersistence.PersistedInventoryBaseline(playerBAccountId, Map.of())
            );
            when(persistence.saveNow(playerAState)).thenReturn(true);
            when(persistence.saveNow(playerBState)).thenReturn(true);
            when(persistence.hasPendingChanges(playerAState)).thenReturn(false);
            when(persistence.hasPendingChanges(playerBState)).thenReturn(false);
            when(repository.commit(any())).thenReturn(result);
            if (failPlayerBReconciliation) {
                doThrow(new IllegalStateException("player-b local reconciliation failed"))
                    .doNothing()
                    .when(inventoryService)
                    .reconcileExternalInventoryEntries(
                        eq(playerBAccountId),
                        eq(result.playerBAffectedInventoryEntryIds()),
                        any()
                    );
            }
            session.setItems(playerAId, List.of(itemStack(1)), List.of(UUID.randomUUID()));
            session.setItems(playerBId, List.of(itemStack(1)), List.of(UUID.randomUUID()));
            service = new TradeService(
                plugin,
                tradeGui,
                cancelConfirmGui,
                goldAmountSettingGui,
                inventoryService,
                currencyService,
                messageService,
                itemReferenceResolver,
                coordinator,
                repository
            );
        }

        @Override
        public void close() throws InterruptedException {
            if (executorService != null) {
                executorService.shutdownNow();
                executorService.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private static ItemStack itemStack(int amount) {
        ItemStack itemStack = mock(ItemStack.class);
        when(itemStack.getType()).thenReturn(Material.STONE);
        when(itemStack.getAmount()).thenReturn(amount);
        when(itemStack.getMaxStackSize()).thenReturn(64);
        when(itemStack.clone()).thenReturn(itemStack);
        return itemStack;
    }
}
