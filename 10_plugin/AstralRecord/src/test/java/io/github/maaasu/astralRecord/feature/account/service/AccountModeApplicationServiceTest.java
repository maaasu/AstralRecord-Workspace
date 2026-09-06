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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
        when(accountService.setMode(initial, AccountMode.PLAYER, updatedBy)).thenReturn(commandResult);
        when(inventoryService.executeLocalPlayerMutation(
            eq(accountUuid),
            org.mockito.ArgumentMatchers.<Supplier<AccountModel>>any()
        )).thenAnswer(invocation -> invocation.<Supplier<AccountModel>>getArgument(1).get());
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));
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
        AstPlayer astPlayer = mock(AstPlayer.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AccountModel initial = account(accountUuid, AccountMode.PLAYER, "初期");
        when(astPlayer.getAccount()).thenReturn(initial);
        when(accountService.setMode(initial, AccountMode.ADMIN, updatedBy)).thenAnswer(invocation -> {
            firstEntered.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return firstResult;
        });
        when(accountService.setMode(initial, AccountMode.PLAYER, updatedBy)).thenAnswer(invocation -> {
            secondEntered.countDown();
            return secondResult;
        });
        when(inventoryService.executeLocalPlayerMutation(
            eq(accountUuid),
            org.mockito.ArgumentMatchers.<Supplier<AccountModel>>any()
        )).thenAnswer(invocation -> invocation.<Supplier<AccountModel>>getArgument(1).get());
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AccountModeApplicationService.PersistedModeChange> first = executor.submit(() -> {
                try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
                    cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));
                    return service.persistModeChange(initial, AccountMode.ADMIN, updatedBy);
                }
            });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<AccountModeApplicationService.PersistedModeChange> second = executor.submit(() -> {
                try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
                    cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));
                    return service.persistModeChange(firstResult, AccountMode.PLAYER, updatedBy);
                }
            });

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS).generation()
                < second.get(5, TimeUnit.SECONDS).generation());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードAPI保存
     * 検証契約: offline保存後にログインした場合は、オンライン進行を保持したmode合成結果だけを反映して再保存しない。
     */
    @Test
    void offlineModeSaveMergesOnlyModeWhenPlayerJoinsBeforeApplication() {
        UUID accountUuid = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountModel offline = account(accountUuid, AccountMode.PLAYER, "取得時");
        AccountModel saved = account(accountUuid, AccountMode.ADMIN, "API保存済み");
        AccountModel joined = account(accountUuid, AccountMode.PLAYER, "ログイン済み");
        AccountModel merged = account(accountUuid, AccountMode.ADMIN, "mode合成済み");
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(joined);
        when(accountService.saveOfflineMode(accountUuid, AccountMode.ADMIN, updatedBy)).thenReturn(saved);
        when(accountService.mergeSavedOfflineMode(joined, saved)).thenReturn(merged);
        when(inventoryService.executeLocalPlayerMutation(
            eq(accountUuid),
            org.mockito.ArgumentMatchers.<Supplier<Void>>any()
        ))
            .thenAnswer(invocation -> invocation.<Supplier<Void>>getArgument(1).get());
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(), List.of(astPlayer));

            AccountModeApplicationService.PersistedModeChange persisted = service.persistOfflineModeChange(
                offline, AccountMode.ADMIN, updatedBy
            );

            assertTrue(persisted.savedRemotely());
            assertTrue(service.applyPersistedMode(persisted));
            verify(accountService).mergeSavedOfflineMode(joined, saved);
            verify(astPlayer).applyAccountMode(merged);
            verify(accountService, never()).requestLocalPlayerSave(accountUuid);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードAPI保存
     * 検証契約: API保存開始前に対象がオンライン化していた場合はoffline保存を拒否する。
     */
    @Test
    void offlineModeSaveIsRejectedWhenAccountBecameOnline() {
        UUID accountUuid = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountModel current = account(accountUuid, AccountMode.PLAYER, "取得時");
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(current);
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));

            assertThrows(IllegalStateException.class, () ->
                service.persistOfflineModeChange(current, AccountMode.ADMIN, updatedBy)
            );
            verify(accountService, never()).saveOfflineMode(accountUuid, AccountMode.ADMIN, updatedBy);
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
