package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountCreateCommandTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント作成
     * 検証契約: 同一ユーザーへの先行作成が未完了の間は後続のAPI作成を拒否し、完了後は次の明示要求を許可する。
     */
    @Test
    void concurrentRequestsCreateOnlyOnceUntilTheFirstRequestCompletes() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AccountService accounts = mock(AccountService.class);
        UserService users = mock(UserService.class);
        UserModel user = mock(UserModel.class);
        AccountModel created = mock(AccountModel.class);
        PlayerMessageService messages = mock(PlayerMessageService.class);
        CommandSender firstSender = mock(CommandSender.class);
        CommandSender secondSender = mock(CommandSender.class);
        UUID userId = UUID.randomUUID();
        ConcurrentLinkedQueue<Runnable> async = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Runnable> sync = new ConcurrentLinkedQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<AccountModel> creation = new CompletableFuture<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getAccountService()).thenReturn(accounts);
        when(plugin.getUserService()).thenReturn(users);
        when(user.getUuid()).thenReturn(userId);
        when(user.getMcid()).thenReturn("Target");
        when(users.getUserByMcid(anyString())).thenReturn(user);
        when(created.getAccountName()).thenReturn("Target123");
        when(created.getSlotIndex()).thenReturn(1);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            sync.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        when(accounts.createAccountAutoAssigned(eq(userId), eq("Target"), any())).thenAnswer(call -> {
            entered.countDown(); return creation.join();
        });
        try (MockedStatic<AstralRecord> pluginStatic = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkitStatic = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> outputStatic = mockStatic(PlayerMessageService.class)) {
            pluginStatic.when(AstralRecord::getInstance).thenReturn(plugin);
            outputStatic.when(PlayerMessageService::getInstance).thenReturn(messages);
            AccountCreateCommand command = new AccountCreateCommand();
            command.executeCommand(firstSender, new String[] {"Target"});
            command.executeCommand(secondSender, new String[] {"TARGET"});
            Runnable first = async.remove();
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                try (MockedStatic<AstralRecord> workerPlugin = mockStatic(AstralRecord.class)) {
                    workerPlugin.when(AstralRecord::getInstance).thenReturn(plugin);
                    first.run();
                }
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                async.remove().run();
                verify(accounts, times(1)).createAccountAutoAssigned(eq(userId), eq("Target"), any());
            } finally {
                creation.complete(created);
                worker.get(10, TimeUnit.SECONDS);
            }
            Runnable notification;
            while ((notification = sync.poll()) != null) notification.run();
            command.executeCommand(firstSender, new String[] {"Target"});
            async.remove().run();
            verify(accounts, times(2)).createAccountAutoAssigned(eq(userId), eq("Target"), any());
            while ((notification = sync.poll()) != null) notification.run();
        }
    }
}
