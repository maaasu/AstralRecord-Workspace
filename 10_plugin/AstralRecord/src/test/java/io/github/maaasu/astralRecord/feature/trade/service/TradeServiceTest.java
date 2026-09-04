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
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitItem;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequestStatus;
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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: 確定待ち中に取引画面を閉じた参加者がいれば、API拒否後は画面のないOPENへ戻さず中止する。
     */
    @Test
    void rejectedCommitAfterClosingGuiCancelsInsteadOfLeavingInvisibleSession() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            context.stubParticipants(bukkit, cache, guard);
            TestContext.registerSession(context.service, context.session);
            when(context.playerA.getOpenInventory().getTopInventory().getHolder()).thenReturn(null);
            context.session.setStatus(TradeSessionStatus.COMMITTING);
            invokeFinish(context.service, context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));
            assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
            assertEquals(null, context.service.getOpenSession(context.playerAId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: 同じsessionのGold設定または中止確認画面を維持している場合、API拒否後はOPENへ戻す。
     */
    @Test
    void rejectedCommitCanResumeFromBothAuxiliaryTradeViews() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            context.stubParticipants(bukkit, cache, guard);
            when(context.playerA.getOpenInventory().getTopInventory().getHolder()).thenReturn(
                new GoldAmountSettingGui.GoldAmountHolder(TradeService.GOLD_AMOUNT_SOURCE_KEY,
                    context.session.getSessionId(), context.playerAId, 0, 0));
            when(context.playerB.getOpenInventory().getTopInventory().getHolder()).thenReturn(
                new TradeCancelConfirmGui.CancelHolder(context.session.getSessionId(), context.playerBId));
            context.session.setStatus(TradeSessionStatus.COMMITTING);
            invokeFinish(context.service, context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));
            assertEquals(TradeSessionStatus.OPEN, context.session.getStatus());
            verify(context.playerA, never()).closeInventory();
            verify(context.playerB, never()).closeInventory();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 4. Close・cancel > ### 処理要点
     * 検証契約: 承認直後の片側GUI表示が同期的に失敗した場合、終了済み取引のGUIを相手に開かない。
     */
    @Test
    void failedFirstParticipantOpenDoesNotOpenCancelledSessionForPartner() throws Exception {
        TestContext context = new TestContext();
        TestContext.clearSessions(context.service);
        TradeRequest request = new TradeRequest(UUID.randomUUID(), context.playerId, "sender",
            context.partnerId, "receiver", Instant.now(), Instant.now().plusSeconds(60));
        TestContext.registerRequest(context.service, request);
        AstPlayer partnerAst = mock(AstPlayer.class);
        AccountModel partnerAccount = mock(AccountModel.class);
        when(partnerAst.getAccount()).thenReturn(partnerAccount);
        when(partnerAccount.getUuid()).thenReturn(context.partnerAccountId);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(3)).run();
            return null;
        }).when(context.tradeGui).open(eq(context.player), any(TradeSession.class), any(Runnable.class), any(Runnable.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter> names =
                 mockStatic(io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.class)) {
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            bukkit.when(() -> Bukkit.getPlayer(context.partnerId)).thenReturn(context.partner);
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            cache.when(() -> AstPlayerCache.get(context.partner)).thenReturn(partnerAst);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.partner)).thenReturn(true);
            names.when(() -> io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.toPlain(context.account))
                .thenReturn("sender");
            names.when(() -> io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter.toPlain(partnerAccount))
                .thenReturn("receiver");
            context.service.acceptTrade(context.partner);
        }
        assertEquals(null, context.service.getOpenSession(context.playerId));
        assertEquals(null, context.service.getOpenSession(context.partnerId));
        verify(context.tradeGui, never()).open(eq(context.partner), any(TradeSession.class), any(Runnable.class), any(Runnable.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 4. Close・cancel > ### 処理要点
     * 検証契約: 通常inventoryから初めて開く取引GUIの手動closeを内部遷移として抑止しない。
     */
    @Test
    void initialTradeOpenDoesNotSuppressFirstManualClose() throws Exception {
        TestContext context = new TestContext();
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            context.service.reopenTrade(context.player);
        }
        assertFalse(context.service.consumeSuppressedClose(context.playerId));
        verify(context.tradeGui).open(eq(context.player), eq(context.session), any(Runnable.class), any(Runnable.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 4. Close・cancel > ### 処理要点
     * 検証契約: Gold画面への遅延遷移が取消された場合、取引と表示予約を終了する。
     */
    @Test
    void cancelledGoldTransitionReleasesSessionAndReservations() throws Exception {
        TestContext context = new TestContext();
        context.session.setItems(context.playerId, List.of(splitItemStack(2, 64)), List.of(context.sourceEntryId));
        when(context.tradeGui.isTradeInventory(context.player.getOpenInventory().getTopInventory())).thenReturn(true);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            context.service.openGoldAmountSetting(context.player);
            ArgumentCaptor<Runnable> cancelled = ArgumentCaptor.forClass(Runnable.class);
            verify(context.goldAmountSettingGui).open(eq(context.player), eq(TradeService.GOLD_AMOUNT_SOURCE_KEY),
                eq(context.session.getSessionId()), eq(0L), eq(0L), any(Runnable.class), cancelled.capture());
            assertTrue(context.service.consumeSuppressedClose(context.playerId));
            cancelled.getValue().run();
        }
        assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
        assertFalse(context.service.consumeSuppressedClose(context.playerId));
        assertEquals(null, context.service.getOpenSession(context.playerId));
        verify(context.inventoryService).releaseHiddenEntryQuantity(context.accountId, context.sourceEntryId, 2);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 4. Close・cancel > ### 処理要点
     * 検証契約: 同じ取引で遷移を差し替えた場合、古い取消通知は新しい画面と取引を中止しない。
     */
    @Test
    void supersededTransitionCannotCancelCurrentSession() throws Exception {
        TestContext context = new TestContext();
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            context.service.reopenTrade(context.player);
            context.service.reopenTrade(context.player);
        }
        ArgumentCaptor<Runnable> cancelled = ArgumentCaptor.forClass(Runnable.class);
        verify(context.tradeGui, times(2)).open(eq(context.player), eq(context.session), any(Runnable.class), cancelled.capture());
        cancelled.getAllValues().getFirst().run();
        assertSame(context.session, context.service.getOpenSession(context.playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Cancel・終了
     * 検証契約: 確定待ち中の退出後にAPI拒否が判明した場合、無人OPEN sessionを残さず中止する。
     */
    @Test
    void rejectedCommitAfterDisconnectCancelsSession() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            TestContext.registerSession(context.service, context.session);
            context.session.setStatus(TradeSessionStatus.COMMITTING);
            invokeFinish(context.service, context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));
            assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
            assertEquals(null, context.service.getOpenSession(context.playerAId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Ready・確定
     * 検証契約: 開始時と現在のaccount identityが異なる場合、API確定を開始せず取引を中止する。
     */
    @Test
    void changedAccountCannotCommitOriginalAccountsOffer() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            context.stubParticipants(bukkit, cache, guard);
            AstPlayer replacement = mock(AstPlayer.class);
            AccountModel account = mock(AccountModel.class);
            when(replacement.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(UUID.randomUUID());
            cache.when(() -> AstPlayerCache.get(context.playerA)).thenReturn(replacement);
            invokeComplete(context.service, context.session);
            assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
            verify(context.repository, never()).commit(any());
            verify(context.inventoryService, never()).refreshManagedInventoryUi(replacement);
            verify(context.playerA, never()).closeInventory();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit > ### 例外・終了条件
     * 検証契約: 旧accountの確定拒否後の中止処理は切替後accountのGUIを閉じない。
     */
    @Test
    void oldAccountsRejectedCommitDoesNotCloseCurrentAccountsGui() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            context.stubParticipants(bukkit, cache, guard);
            AstPlayer current = mock(AstPlayer.class);
            AccountModel account = mock(AccountModel.class);
            when(current.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(UUID.randomUUID());
            cache.when(() -> AstPlayerCache.get(context.playerA)).thenReturn(current);
            context.session.setStatus(TradeSessionStatus.COMMITTING);
            invokeFinish(context.service, context.session,
                new TradeRepository.TradeCommitRejectedException(409, "trade.inventory_missing"));
            assertEquals(TradeSessionStatus.CANCELLED, context.session.getStatus());
            verify(context.playerA, never()).closeInventory();
            verify(context.playerB).closeInventory();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 1. 招待から session 開始 > ### 例外・終了条件
     * 検証契約: API確定中の参加者からの新しい招待も拒否し、既存sessionを維持する。
     */
    @Test
    void committingParticipantCannotRequestAnotherTrade() throws Exception {
        TestContext context = new TestContext();
        context.session.setStatus(TradeSessionStatus.COMMITTING);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.partner)).thenReturn(true);
            context.service.requestTrade(context.player, context.partner);
        }
        assertTrue(TestContext.requestsOf(context.service).isEmpty());
        assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
        verify(context.messageService).send(context.player, PlayerMsgId.P_6203);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 1. 招待から session 開始 > ### 例外・終了条件
     * 検証契約: 申請後に送信者が確定中となった場合、承認しても既存sessionを上書きしない。
     */
    @Test
    void cannotAcceptRequestFromCommittingParticipant() throws Exception {
        TestContext context = new TestContext();
        context.session.setStatus(TradeSessionStatus.COMMITTING);
        TradeRequest request = new TradeRequest(UUID.randomUUID(), context.playerId, "sender",
            context.partnerId, "receiver", Instant.now(), Instant.now().plusSeconds(60));
        TestContext.registerRequest(context.service, request);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.partner)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            context.service.acceptTrade(context.partner);
        }
        assertEquals(TradeRequestStatus.CANCELLED, request.getStatus());
        assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
        verify(context.messageService).send(context.partner, PlayerMsgId.P_6203);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 2. Item／gold 提示 > ### 例外・終了条件
     * 検証契約: 表示予約の追加が失敗しても、既存提示のsource entry IDと数量を維持する。
     */
    @Test
    void failedOfferPreservesExistingSourceEntryIds() throws Exception {
        TestContext context = new TestContext();
        context.session.setItems(context.playerId, List.of(splitItemStack(2, 64)), List.of(context.sourceEntryId));
        doThrow(new IllegalStateException("render failed")).when(context.inventoryService)
            .hideOwnedEntryQuantityFromGui(any(), any(), anyInt());
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            assertFalse(context.service.offerOwnedItem(context.player, 9, ClickType.LEFT, splitItemStack(4, 64)));
        }
        assertEquals(List.of(new TradeCommitItem(context.sourceEntryId, 2)), context.session.getCommitItems(context.playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 2. Item／gold 提示 > ### 例外・終了条件
     * 検証契約: 取り下げ予約解除が失敗しても、提示元entry IDと数量を元のまま維持する。
     */
    @Test
    void failedWithdrawalPreservesExistingSourceEntryIds() throws Exception {
        TestContext context = new TestContext();
        context.session.setItems(context.playerId, List.of(splitItemStack(2, 64)), List.of(context.sourceEntryId));
        doThrow(new IllegalStateException("render failed")).when(context.inventoryService)
            .restoreHiddenEntryQuantityToGui(any(), any(), anyInt());
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger =
                 mockStatic(io.github.maaasu.astralRecord.infrastructure.logging.Logger.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            assertFalse(context.service.withdrawOfferedItem(context.player, 0, ClickType.LEFT));
        }
        assertEquals(List.of(new TradeCommitItem(context.sourceEntryId, 2)), context.session.getCommitItems(context.playerId));
    }

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
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_1-モデル定義.md
     * 章・見出し: # 22_1-モデル定義 > ## TradeSession
     * 検証契約: 同一 source inventory entry を複数の提示 clone に分割しても、API へ送る明細は
     * source entry 単位に一つへ集約し、数量を合算する。
     */
    @Test
    void commitItemsAggregateRepeatedSourceEntryIdsForApiRequest() throws Exception {
        TestContext context = new TestContext();
        UUID secondSourceEntryId = UUID.randomUUID();
        context.session.setItems(
            context.playerId,
            List.of(itemStack(2), itemStack(3), itemStack(4)),
            List.of(context.sourceEntryId, context.sourceEntryId, secondSourceEntryId)
        );

        assertTrue(context.session.hasValidCommitItems(context.playerId));
        assertEquals(
            List.of(
                new TradeCommitItem(context.sourceEntryId, 5L),
                new TradeCommitItem(secondSourceEntryId, 4L)
            ),
            context.session.getCommitItems(context.playerId)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_1-モデル定義.md
     * 章・見出し: # 22_1-モデル定義 > ## TradeSession
     * 検証契約: 一つの source inventory entry が複数の表示stackへ分割された場合も、各cloneへ同じ
     * source entry IDを対応付け、API確定時に全数量を合算できる。
     */
    @Test
    void splitEscrowItemsKeepSourceEntryIdForEachApiQuantity() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = splitItemStack(5, 2);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_RIGHT, displayed);
        }

        assertTrue(offered);
        assertEquals(3, context.session.getItems(context.playerId).size());
        for (int index = 0; index < 3; index++) {
            assertEquals(
                context.sourceEntryId,
                context.session.getItemSourceEntryId(context.playerId, index)
            );
        }
        assertEquals(
            List.of(new TradeCommitItem(context.sourceEntryId, 5L)),
            context.session.getCommitItems(context.playerId)
        );
        verify(context.inventoryService).hideOwnedEntryQuantityFromGui(context.astPlayer, context.sourceEntryId, 5);
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
     * 章・見出し: # 22_3-メソッド仕様 > ## Ready・確定
     * 検証契約: item または Gold の提示変更では ready を解除せず、本人の ready toggle だけが
     * 準備解除を行う。
     */
    @Test
    void offerAndGoldChangesKeepReadyUntilPlayerExplicitlyCancelsIt() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);
        context.session.setReady(context.playerId, true);
        context.session.setReady(context.partnerId, true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AccountModeGuard> accountModeGuard = mockStatic(AccountModeGuard.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);
            bukkit.when(() -> Bukkit.getPlayer(context.partnerId)).thenReturn(context.partner);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);

            assertTrue(context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed));
            context.session.setGoldAmount(context.playerId, 50L);

            assertTrue(context.session.isPlayerAReady());
            assertTrue(context.session.isPlayerBReady());

            context.service.toggleReady(context.player);
        }

        assertFalse(context.session.isPlayerAReady());
        assertTrue(context.session.isPlayerBReady());
        verify(context.messageService).send(context.player, PlayerMsgId.P_6206);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_2-ユースケース.md
     * 章・見出し: # 22_2-ユースケース > ## UC-22-01 招待する
     * 検証契約: 拠点ワールド以外にいる参加者がいる場合は request を作成しない。
     */
    @Test
    void requestTradeRejectsParticipantOutsideAllowedWorld() throws Exception {
        TestContext context = new TestContext();
        when(context.worldService.findByBukkitWorld(context.partnerWorld)).thenReturn(
            worldDefinition("field", WorldType.OVERWORLD)
        );

        try (MockedStatic<AccountModeGuard> accountModeGuard = mockStatic(AccountModeGuard.class)) {
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.partner)).thenReturn(true);

            context.service.requestTrade(context.player, context.partner);
        }

        assertTrue(TestContext.requestsOf(context.service).isEmpty());
        verify(context.messageService).send(context.player, PlayerMsgId.P_6210);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_2-ユースケース.md
     * 章・見出し: # 22_2-ユースケース > ## UC-22-02 招待を承認する
     * 検証契約: accept 時にも申請者・承認者双方の現在ワールドを再検証し、申請後の移動で
     * 許可外となった申請は開始しない。
     */
    @Test
    void acceptTradeRevalidatesRequesterWorld() throws Exception {
        TestContext context = new TestContext();
        TestContext.clearSessions(context.service);
        TradeRequest request = new TradeRequest(
            UUID.randomUUID(),
            context.playerId,
            "sender",
            context.partnerId,
            "accepter",
            Instant.now(),
            Instant.now().plusSeconds(60L)
        );
        TestContext.registerRequest(context.service, request);
        when(context.worldService.findByBukkitWorld(context.playerWorld)).thenReturn(
            worldDefinition("field", WorldType.OVERWORLD)
        );

        try (MockedStatic<AccountModeGuard> accountModeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.partner)).thenReturn(true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(context.playerId)).thenReturn(context.player);

            context.service.acceptTrade(context.partner);
        }

        assertEquals(TradeRequestStatus.CANCELLED, request.getStatus());
        assertTrue(TestContext.requestsOf(context.service).isEmpty());
        verify(context.messageService).send(context.partner, PlayerMsgId.P_6210);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_2-ユースケース.md
     * 章・見出し: # 22_2-ユースケース > ## UC-22-01 招待する
     * 検証契約: スキルツリーサービスが認識する専用ワールドは拠点ワールドと同じく許可する。
     */
    @Test
    void skillTreeWorldIsAllowedForTrade() throws Exception {
        TestContext context = new TestContext();
        var skillTreeService = mock(io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService.class);
        when(context.plugin.getSkillTreeService()).thenReturn(skillTreeService);
        when(context.worldService.findByBukkitWorld(context.playerWorld)).thenReturn(
            worldDefinition("hub", WorldType.HUB)
        );
        when(skillTreeService.isSkillTreeWorld(context.playerWorld)).thenReturn(true);

        assertTrue(context.service.isTradeAllowedWorld(context.player));
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
            verify(context.inventoryService).reconcileTradeInventoryEntries(
                eq(context.playerAAccountId),
                eq(context.result.playerAAffectedInventoryEntryIds()),
                any()
            );
            verify(context.inventoryService).reconcileTradeInventoryEntries(
                eq(context.playerBAccountId),
                eq(context.result.playerBAffectedInventoryEntryIds()),
                any()
            );
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_1-モデル定義.md
     * 章・見出し: # 22_1-モデル定義 > ## TradeSession
     * 検証契約: 同一 source inventory entry を複数提示した session から commit する場合、API request
     * の同一 player 側明細へ重複 ID を送らず数量を合算する。
     */
    @Test
    void commitRequestDoesNotDuplicateSourceEntryIds() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> accountModeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            context.stubParticipants(bukkit, cache, accountModeGuard);
            UUID repeatedSourceEntryId = UUID.randomUUID();
            context.session.setItems(
                context.playerAId,
                List.of(itemStack(2), itemStack(3)),
                List.of(repeatedSourceEntryId, repeatedSourceEntryId)
            );
            ItemModel itemModel = mock(ItemModel.class);
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(context.itemReferenceResolver.resolveItemModel(any(ItemStack.class))).thenReturn(itemModel);
            context.session.setReady(context.playerAId, true);
            context.session.setReady(context.playerBId, true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.playerA)).thenReturn(true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.playerB)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(context.playerAId)).thenReturn(context.playerA);
            bukkit.when(() -> Bukkit.getPlayer(context.playerBId)).thenReturn(context.playerB);
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));

            invokeComplete(context.service, context.session);

            ArgumentCaptor<TradeCommitRequest> requests = ArgumentCaptor.forClass(TradeCommitRequest.class);
            verify(context.repository).commit(requests.capture());
            assertEquals(
                List.of(new TradeCommitItem(repeatedSourceEntryId, 5L)),
                requests.getValue().playerAItems()
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
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<io.github.maaasu.astralRecord.infrastructure.logging.Logger> logger = mockStatic(
                 io.github.maaasu.astralRecord.infrastructure.logging.Logger.class
             )) {
            context.stubParticipants(bukkit, cache, guard);
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Ready・確定
     * 検証契約: トレード確定は通常 inventory の容量シミュレーションを行わず、満杯でも API
     * transaction と再同期を開始する。
     */
    @Test
    void fullInventoryDoesNotPreventTradeCommitFromStarting() throws Exception {
        try (CommitContext context = new CommitContext(false, false);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<AccountModeGuard> accountModeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            context.stubParticipants(bukkit, cache, accountModeGuard);
            ItemModel itemModel = mock(ItemModel.class);
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(context.itemReferenceResolver.resolveItemModel(any(ItemStack.class))).thenReturn(itemModel);
            context.session.setReady(context.playerAId, true);
            context.session.setReady(context.playerBId, true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.playerA)).thenReturn(true);
            accountModeGuard.when(() -> AccountModeGuard.isGameplayPlayer(context.playerB)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(context.playerAId)).thenReturn(context.playerA);
            bukkit.when(() -> Bukkit.getPlayer(context.playerBId)).thenReturn(context.playerB);
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));

            invokeComplete(context.service, context.session);

            assertEquals(TradeSessionStatus.COMMITTING, context.session.getStatus());
            verify(context.repository).commit(any());
            verify(context.inventoryService, never()).canAddItemToNormalInventory(any(), any(), anyInt());
            verify(context.inventoryService, never()).returnItemToOwnedInventory(any(), any(ItemStack.class));
            verify(context.inventoryService, never()).snapshotState(any());
        }
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

    private static void invokeComplete(TradeService service, TradeSession session) throws Exception {
        Method complete = TradeService.class.getDeclaredMethod("completeTrade", TradeSession.class);
        complete.setAccessible(true);
        complete.invoke(service, session);
    }

    private static WorldMasterData worldDefinition(String id, WorldType type) {
        return new WorldMasterData(
            1,
            id,
            id,
            type,
            "",
            "",
            false,
            false,
            0,
            false,
            false,
            false,
            false,
            WorldSpawnLocation.defaultLocation(),
            "",
            null,
            null,
            null
        );
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
        private final WorldService worldService = mock(WorldService.class);
        private final World playerWorld = mock(World.class);
        private final World partnerWorld = mock(World.class);
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
            for (Player participant : List.of(player, partner)) {
                var view = mock(org.bukkit.inventory.InventoryView.class);
                when(view.getTopInventory()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
                when(participant.getOpenInventory()).thenReturn(view);
            }
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(playerWorld);
            when(partner.getUniqueId()).thenReturn(partnerId);
            when(partner.isOnline()).thenReturn(true);
            when(partner.getWorld()).thenReturn(partnerWorld);
            when(plugin.getWorldService()).thenReturn(worldService);
            when(worldService.findByBukkitWorld(playerWorld)).thenReturn(worldDefinition("base", WorldType.BASE));
            when(worldService.findByBukkitWorld(partnerWorld)).thenReturn(worldDefinition("base", WorldType.BASE));
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
        private static void clearSessions(TradeService service) throws Exception {
            Field sessionsField = TradeService.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ((Map<UUID, TradeSession>) sessionsField.get(service)).clear();
            Field activeField = TradeService.class.getDeclaredField("activeSessionByPlayer");
            activeField.setAccessible(true);
            ((Map<UUID, UUID>) activeField.get(service)).clear();
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
        private final Player playerA = mock(Player.class);
        private final Player playerB = mock(Player.class);
        private final WorldService worldService = mock(WorldService.class);
        private final World playerAWorld = mock(World.class);
        private final World playerBWorld = mock(World.class);
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

        private void stubParticipants(MockedStatic<Bukkit> bukkit, MockedStatic<AstPlayerCache> cache,
                                      MockedStatic<AccountModeGuard> guard) {
            for (Player player : List.of(playerA, playerB)) {
                UUID accountId = player == playerA ? playerAAccountId : playerBAccountId;
                AstPlayer astPlayer = mock(AstPlayer.class);
                AccountModel account = mock(AccountModel.class);
                when(astPlayer.getAccount()).thenReturn(account);
                when(account.getUuid()).thenReturn(accountId);
                when(player.isOnline()).thenReturn(true);
                var tradeHolder = new TradeGui.TradeHolder(session.getSessionId(), player.getUniqueId());
                when(player.getOpenInventory().getTopInventory().getHolder()).thenReturn(tradeHolder);
                bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
                guard.when(() -> AccountModeGuard.isGameplayPlayer(player)).thenReturn(true);
            }
        }

        private CommitContext(boolean failPlayerBReconciliation) {
            this(failPlayerBReconciliation, true);
        }

        private CommitContext(boolean failPlayerBReconciliation, boolean useSingleThreadExecutor) {
            for (Player participant : List.of(playerA, playerB)) {
                var view = mock(org.bukkit.inventory.InventoryView.class);
                when(view.getTopInventory()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
                when(participant.getOpenInventory()).thenReturn(view);
            }
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
            when(playerA.getUniqueId()).thenReturn(playerAId);
            when(playerA.getWorld()).thenReturn(playerAWorld);
            when(playerB.getUniqueId()).thenReturn(playerBId);
            when(playerB.getWorld()).thenReturn(playerBWorld);
            when(plugin.getWorldService()).thenReturn(worldService);
            when(worldService.findByBukkitWorld(playerAWorld)).thenReturn(worldDefinition("base", WorldType.BASE));
            when(worldService.findByBukkitWorld(playerBWorld)).thenReturn(worldDefinition("base", WorldType.BASE));
            if (failPlayerBReconciliation) {
                doThrow(new IllegalStateException("player-b local reconciliation failed"))
                    .doNothing()
                    .when(inventoryService)
                    .reconcileTradeInventoryEntries(
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

    private static ItemStack splitItemStack(int amount, int maxStackSize) {
        ItemStack itemStack = mock(ItemStack.class);
        AtomicInteger currentAmount = new AtomicInteger(amount);
        when(itemStack.getType()).thenReturn(Material.STONE);
        when(itemStack.getAmount()).thenAnswer(ignored -> currentAmount.get());
        when(itemStack.getMaxStackSize()).thenReturn(maxStackSize);
        doAnswer(invocation -> {
            currentAmount.set((Integer) invocation.getArgument(0));
            return null;
        }).when(itemStack).setAmount(anyInt());
        when(itemStack.clone()).thenAnswer(ignored -> splitItemStack(currentAmount.get(), maxStackSize));
        return itemStack;
    }
}
