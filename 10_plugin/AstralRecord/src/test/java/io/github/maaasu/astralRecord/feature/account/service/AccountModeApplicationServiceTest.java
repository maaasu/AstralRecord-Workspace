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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountModeApplicationServiceTest {

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
        when(accountService.setMode(accountUuid, AccountMode.ADMIN, updatedBy)).thenReturn(eventResult);
        when(accountService.setMode(accountUuid, AccountMode.PLAYER, updatedBy)).thenReturn(commandResult);
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);

        AccountModeApplicationService.PersistedModeChange delayedEvent = service.persistModeChange(
            accountUuid,
            AccountMode.ADMIN,
            updatedBy
        );
        AccountModeApplicationService.PersistedModeChange newerCommand = service.persistModeChange(
            accountUuid,
            AccountMode.PLAYER,
            updatedBy
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(astPlayer));

            assertFalse(service.applyPersistedMode(delayedEvent));
            verify(astPlayer, never()).applyAccountMode(eventResult);

            assertTrue(service.applyPersistedMode(newerCommand));
            verify(astPlayer).applyAccountMode(commandResult);
            verify(inventoryService).applyInventoriesToGui(astPlayer);
        }
    }

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
        when(accountService.setMode(accountUuid, AccountMode.ADMIN, updatedBy)).thenAnswer(invocation -> {
            firstEntered.countDown();
            releaseFirst.await(1, TimeUnit.SECONDS);
            return firstResult;
        });
        when(accountService.setMode(accountUuid, AccountMode.PLAYER, updatedBy)).thenAnswer(invocation -> {
            secondEntered.countDown();
            return secondResult;
        });
        AccountModeApplicationService service = new AccountModeApplicationService(accountService, inventoryService);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AccountModeApplicationService.PersistedModeChange> first = executor.submit(() ->
                service.persistModeChange(accountUuid, AccountMode.ADMIN, updatedBy)
            );
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<AccountModeApplicationService.PersistedModeChange> second = executor.submit(() ->
                service.persistModeChange(accountUuid, AccountMode.PLAYER, updatedBy)
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
