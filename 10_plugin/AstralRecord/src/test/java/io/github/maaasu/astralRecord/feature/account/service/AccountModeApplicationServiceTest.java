package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountModeApplicationServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウントモード更新
     * 検証契約: 古いローカル確定世代の反映を拒否し、最新世代だけをオンラインへ反映する。
     */
    @Test
    void newerPersistedModeSupersedesDelayedOlderApplication() {
        UUID accountUuid = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountModel initial = account(accountUuid, AccountMode.PLAYER, "初期");
        AccountModel eventResult = account(accountUuid, AccountMode.ADMIN, "イベント更新");
        AccountModel commandResult = account(accountUuid, AccountMode.PLAYER, "コマンド更新");
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(initial);
        when(accountService.setMode(initial, AccountMode.ADMIN, updatedBy)).thenReturn(eventResult);
        when(accountService.setMode(eventResult, AccountMode.PLAYER, updatedBy)).thenReturn(commandResult);
        when(inventoryService.executeLocalPlayerMutation(eq(accountUuid), any(Supplier.class)))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of());
            AccountModeApplicationService.PersistedModeChange delayedEvent = service.persistModeChange(
                initial,
                AccountMode.ADMIN,
                updatedBy
            );
            AccountModeApplicationService.PersistedModeChange newerCommand = service.persistModeChange(
                eventResult,
                AccountMode.PLAYER,
                updatedBy
            );

            cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));

            assertFalse(service.applyPersistedMode(delayedEvent));
            verify(astPlayer, never()).applyAccountMode(eventResult);

            assertTrue(service.applyPersistedMode(newerCommand));
            verify(astPlayer).applyAccountMode(commandResult);
            verify(inventoryService).applyInventoriesToGui(astPlayer);
            verify(accountService).requestLocalPlayerSave(accountUuid);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウントモード更新
     * 検証契約: 同一accountのモード確定を直列化し、先行mutation完了前に後続mutationを開始しない。
     */
    @Test
    void sameAccountPersistenceIsSerialized() throws Exception {
        UUID accountUuid = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountModel firstResult = account(accountUuid, AccountMode.ADMIN, "先行更新");
        AccountModel secondResult = account(accountUuid, AccountMode.PLAYER, "後続更新");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AccountModel initial = account(accountUuid, AccountMode.PLAYER, "初期");
        when(accountService.setMode(initial, AccountMode.ADMIN, updatedBy)).thenAnswer(invocation -> {
            firstEntered.countDown();
            releaseFirst.await(1, TimeUnit.SECONDS);
            return firstResult;
        });
        when(accountService.setMode(firstResult, AccountMode.PLAYER, updatedBy)).thenAnswer(invocation -> {
            secondEntered.countDown();
            return secondResult;
        });
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of());
            Future<AccountModeApplicationService.PersistedModeChange> first = executor.submit(() ->
                service.persistModeChange(initial, AccountMode.ADMIN, updatedBy)
            );
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<AccountModeApplicationService.PersistedModeChange> second = executor.submit(() ->
                service.persistModeChange(firstResult, AccountMode.PLAYER, updatedBy)
            );

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();

            assertTrue(first.get(1, TimeUnit.SECONDS).generation()
                < second.get(1, TimeUnit.SECONDS).generation());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private AccountModel account(UUID accountUuid, AccountMode mode, String name) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountUuid);
        when(account.getMode()).thenReturn(mode);
        when(account.getAccountName()).thenReturn(name);
        return account;
    }
}
