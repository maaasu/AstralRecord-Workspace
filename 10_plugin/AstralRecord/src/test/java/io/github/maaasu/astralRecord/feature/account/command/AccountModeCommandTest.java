package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountModeCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントモード変更
     * 検証契約: offline対象の成功通知は非同期API保存とメインスレッド反映の双方が完了した後だけ送信する。
     */
    @Test
    void offlineModeSuccessIsSentOnlyAfterRemotePersistenceCompletes() {
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Player sender = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel current = account(accountId, AccountMode.PLAYER, "before");
        AccountModel saved = account(accountId, AccountMode.ADMIN, "after");
        when(sender.getUniqueId()).thenReturn(playerId);
        when(sender.getName()).thenReturn("admin");
        when(astPlayer.getAccount()).thenReturn(current);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        when(astPlayer.hasPermissionLevel(99)).thenReturn(true);

        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AccountService accountService = mock(AccountService.class);
        UserService userService = mock(UserService.class);
        AccountModeApplicationService applicationService = mock(AccountModeApplicationService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        ConcurrentLinkedQueue<Runnable> asyncTasks = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Runnable> syncTasks = new ConcurrentLinkedQueue<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getAccountService()).thenReturn(accountService);
        when(plugin.getUserService()).thenReturn(userService);
        when(plugin.getAccountModeApplicationService()).thenReturn(applicationService);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            syncTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(accountService.getAccount(accountId)).thenReturn(current);
        when(applicationService.isAccountOnline(accountId)).thenReturn(false);
        AccountModeApplicationService.PersistedModeChange persisted =
            new AccountModeApplicationService.PersistedModeChange(saved, 1L, true);
        when(applicationService.persistOfflineModeChange(current, AccountMode.ADMIN, playerId))
            .thenReturn(persisted);
        when(applicationService.applyPersistedMode(persisted)).thenReturn(true);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new AccountModeCommand().onCommand(sender, null, "account", new String[] {"ADMIN"});
            assertEquals(1, asyncTasks.size());
            verify(messageService, never()).sendRaw(eq((CommandSender) sender), anyString());

            asyncTasks.remove().run();
            assertEquals(1, syncTasks.size());
            syncTasks.remove().run();
            assertEquals(1, asyncTasks.size());
            verify(messageService, never()).sendRaw(eq((CommandSender) sender), anyString());

            asyncTasks.remove().run();
            assertEquals(1, syncTasks.size());
            verify(messageService, never()).sendRaw(eq((CommandSender) sender), anyString());

            syncTasks.remove().run();
            verify(applicationService).persistOfflineModeChange(current, AccountMode.ADMIN, playerId);
            verify(applicationService).applyPersistedMode(persisted);
            verify(messageService).sendRaw(eq((CommandSender) sender), anyString());
        }
    }

    private AccountModel account(UUID accountId, AccountMode mode, String name) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getMode()).thenReturn(mode);
        when(account.getAccountName()).thenReturn(name);
        when(account.getSlotIndex()).thenReturn(0);
        return account;
    }
}
