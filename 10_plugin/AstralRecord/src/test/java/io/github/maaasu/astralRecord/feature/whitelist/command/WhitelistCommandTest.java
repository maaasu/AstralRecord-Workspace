package io.github.maaasu.astralRecord.feature.whitelist.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhitelistCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 6. whitelist メンテナンス切替
     * 検証契約: コンソールの引数なし実行は現在値を反転し、falseをサービスへ保存する。
     */
    @Test
    void noArgumentTogglesWhitelistForConsole() {
        WhitelistService service = mock(WhitelistService.class);
        CommandSender sender = mock(CommandSender.class);
        when(service.isEnabled()).thenReturn(true);

        new WhitelistCommand(service).onCommand(sender, null, "whitelist", new String[0]);

        verify(service).setEnabled(false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 6. whitelist メンテナンス切替
     * 検証契約: enable の第2引数 true/false は指定値をサービスへ保存する。
     */
    @Test
    void enableArgumentSetsExplicitWhitelistState() {
        WhitelistService service = mock(WhitelistService.class);
        CommandSender sender = mock(CommandSender.class);

        new WhitelistCommand(service).onCommand(sender, null, "whitelist", new String[] {"enable", "true"});

        verify(service).setEnabled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 6. whitelist メンテナンス切替
     * 検証契約: true/false以外の引数は状態を変更せず、エラーとusageを実行者へ返す。
     */
    @Test
    void rejectsNonBooleanArgumentWithoutChangingState() {
        WhitelistService service = mock(WhitelistService.class);
        CommandSender sender = mock(CommandSender.class);

        new WhitelistCommand(service).onCommand(sender, null, "whitelist", new String[] {"yes"});

        verify(service, never()).setEnabled(true);
        verify(service, never()).setEnabled(false);
        ArgumentCaptor<String> messagesCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, times(2)).sendMessage(messagesCaptor.capture());
        List<String> messages = messagesCaptor.getAllValues();
        assertTrue(messages.stream().anyMatch(message ->
            message.contains("whitelist の引数が不正です。使用方法を確認してください。")
        ));
        assertTrue(messages.stream().anyMatch(message ->
            message.contains("使い方: §e/whitelist [enable <true|false>|user <add|remove> <playerName>]")
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: DB登録済みユーザー名の追加はAPI検索後にUUIDをwhitelist設定へ反映する。
     */
    @Test
    void addsDatabaseUserToWhitelist() {
        UUID playerUuid = UUID.randomUUID();
        WhitelistService service = mock(WhitelistService.class);
        UserService userService = mock(UserService.class);
        UserModel user = mock(UserModel.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(userService.getUserByMcid("Alice")).thenReturn(user);
        when(user.getUuid()).thenReturn(playerUuid);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            new WhitelistCommand(service, userService).onCommand(
                sender,
                null,
                "whitelist",
                new String[] {"user", "add", "Alice"}
            );
        }

        verify(userService).getUserByMcid("Alice");
        verify(service).addWhitelistUser(playerUuid);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: DB登録済みユーザー名の削除はAPI検索後にUUIDをwhitelist設定から除去する。
     */
    @Test
    void removesDatabaseUserFromWhitelist() {
        UUID playerUuid = UUID.randomUUID();
        WhitelistService service = mock(WhitelistService.class);
        UserService userService = mock(UserService.class);
        UserModel user = mock(UserModel.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(userService.getUserByMcid("Alice")).thenReturn(user);
        when(user.getUuid()).thenReturn(playerUuid);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            new WhitelistCommand(service, userService).onCommand(
                sender,
                null,
                "whitelist",
                new String[] {"user", "remove", "Alice"}
            );
        }

        verify(userService).getUserByMcid("Alice");
        verify(service).removeWhitelistUser(playerUuid);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: DB未登録ユーザー名の追加はwhitelist設定を変更せず、対象未検出メッセージを返す。
     */
    @Test
    void rejectsUnknownDatabaseUserWithoutChangingWhitelist() {
        WhitelistService service = mock(WhitelistService.class);
        UserService userService = mock(UserService.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(userService.getUserByMcid("Unknown")).thenReturn(null);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            new WhitelistCommand(service, userService).onCommand(
                sender,
                null,
                "whitelist",
                new String[] {"user", "add", "Unknown"}
            );
        }

        verify(service, never()).addWhitelistUser(any(UUID.class));
        verify(service, never()).removeWhitelistUser(any(UUID.class));
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("DBに登録されたプレイヤーが見つかりません"));
    }

    private void runSchedulerImmediately(AstralRecord plugin, BukkitScheduler scheduler) {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
    }
}
