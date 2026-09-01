package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BanCommandTest {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN実行
     * 検証契約: temporary と正の整数日数を指定した BAN は API 更新へ false と日数後の期限を渡し、更新成功後にオンライン対象をキックする。
     */
    @Test
    void temporaryBanUpdatesExpiryAndKicksOnlinePlayer() {
        UUID targetUuid = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        UserModel before = mock(UserModel.class);
        UserModel updated = mock(UserModel.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Player online = mock(Player.class);
        when(userService.getUserByMcid("Alice")).thenReturn(before);
        when(before.getUuid()).thenReturn(targetUuid);
        when(userService.setBan(eq(targetUuid), eq(false), any(LocalDateTime.class), eq(SystemUser.INSTANCE.getUuid())))
                .thenReturn(updated);
        when(updated.getUuid()).thenReturn(targetUuid);
        when(updated.getMcid()).thenReturn("Alice");
        when(updated.getBanIndefinite()).thenReturn(false);
        LocalDateTime expiry = LocalDateTime.now().plusDays(7).withNano(0);
        when(updated.getBanDate()).thenReturn(expiry);
        when(online.isOnline()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BanCommandTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(online);

            new BanCommand(userService).onCommand(
                    sender,
                    null,
                    "ban",
                    new String[] {"Alice", "temporary", "7"}
            );
        }

        ArgumentCaptor<LocalDateTime> expiryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userService).setBan(
                eq(targetUuid),
                eq(false),
                expiryCaptor.capture(),
                eq(SystemUser.INSTANCE.getUuid())
        );
        LocalDateTime requestedExpiry = expiryCaptor.getValue();
        assertTrue(!requestedExpiry.isBefore(LocalDateTime.now().plusDays(6)));
        assertTrue(!requestedExpiry.isAfter(LocalDateTime.now().plusDays(8)));
        verify(online).kick(any(net.kyori.adventure.text.Component.class));
        verify(sender, times(1)).sendMessage(any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN実行
     * 検証契約: indefinite を指定した BAN は API 更新へ true と null の期限を渡し、日数引数を必要としない。
     */
    @Test
    void indefiniteBanSendsIndefiniteFlagAndNullExpiry() {
        UUID targetUuid = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        UserModel before = mock(UserModel.class);
        UserModel updated = mock(UserModel.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(userService.getUserByMcid("Alice")).thenReturn(before);
        when(before.getUuid()).thenReturn(targetUuid);
        when(userService.setBan(
                eq(targetUuid),
                eq(true),
                isNull(LocalDateTime.class),
                eq(SystemUser.INSTANCE.getUuid())
        )).thenReturn(updated);
        when(updated.getUuid()).thenReturn(targetUuid);
        when(updated.getMcid()).thenReturn("Alice");
        when(updated.getBanIndefinite()).thenReturn(true);
        when(updated.getBanDate()).thenReturn(null);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BanCommandTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(null);

            new BanCommand(userService).onCommand(
                    sender,
                    null,
                    "ban",
                    new String[] {"Alice", "indefinite"}
            );
        }

        verify(userService).setBan(
                eq(targetUuid),
                eq(true),
                isNull(LocalDateTime.class),
                eq(SystemUser.INSTANCE.getUuid())
        );
        verify(sender, times(1)).sendMessage(any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN実行
     * 検証契約: MCID が未登録の場合は BAN 更新を行わず、DBユーザー未検出を実行者へ通知する。
     */
    @Test
    void unknownMcidDoesNotUpdateBan() {
        UserService userService = mock(UserService.class);
        CommandSender sender = mock(CommandSender.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(userService.getUserByMcid("Unknown")).thenReturn(null);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BanCommandTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);

            new BanCommand(userService).onCommand(
                    sender,
                    null,
                    "ban",
                    new String[] {"Unknown", "indefinite"}
            );
        }

        verify(userService, never()).setBan(
                any(UUID.class),
                anyBoolean(),
                any(LocalDateTime.class),
                any(UUID.class)
        );
        verify(sender).sendMessage(any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN実行
     * 検証契約: BANはADMIN権限のプレイヤーまたはコンソールだけが利用でき、一般プレイヤーは実行できない。
     */
    @Test
    void requiresAdminPermissionForPlayersAndAllowsConsole() {
        UserService userService = mock(UserService.class);
        BanCommand command = new BanCommand(userService);

        Player regularPlayer = mock(Player.class);
        UUID regularUuid = UUID.randomUUID();
        when(regularPlayer.getUniqueId()).thenReturn(regularUuid);
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        when(regularAstPlayer.getBukkit()).thenReturn(regularPlayer);
        when(regularAstPlayer.hasPermissionLevel(99)).thenReturn(false);
        AstPlayerCache.put(regularAstPlayer);

        assertFalse(command.canUse(regularPlayer));

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertTrue(command.canUse(console));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN補完候補取得
     * 検証契約: コンソールのBAN補完は期間フラグと有期限日数の候補を返す。
     */
    @Test
    void completesBanDurationAndTemporaryDays() {
        BanTabCompleter completer = new BanTabCompleter(mock(UserService.class));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        assertEquals(
                List.of("indefinite", "temporary"),
                completer.onTabComplete(console, null, "ban", new String[] {"Alice", ""})
        );
        assertEquals(
                List.of("1", "7", "30"),
                completer.onTabComplete(console, null, "ban", new String[] {"Alice", "temporary", ""})
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN補完候補取得
     * 検証契約: ADMINプレイヤーとコンソールのMCID補完は、UserServiceの非同期検索結果を候補として返す。
     */
    @Test
    void loadsMcidCandidatesForAdminPlayerAndConsoleAsynchronously() {
        UserService userService = mock(UserService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        Player adminPlayer = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        List<String> expected = List.of("Alfred", "Alice");

        when(userService.getMcidSuggestions("Al")).thenReturn(expected);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(adminPlayer.getUniqueId()).thenReturn(adminUuid);
        when(adminAstPlayer.getBukkit()).thenReturn(adminPlayer);
        when(adminAstPlayer.hasAdminPermission()).thenReturn(true);
        AstPlayerCache.put(adminAstPlayer);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            BanTabCompleter completer = new BanTabCompleter(userService);

            assertEquals(
                    expected,
                    completer.onTabCompleteAsync(console, null, "ban", new String[] {"Al"}).join()
            );
            assertEquals(
                    expected,
                    completer.onTabCompleteAsync(adminPlayer, null, "ban", new String[] {"Al"}).join()
            );
        }

        verify(userService, times(2)).getMcidSuggestions("Al");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN補完候補取得
     * 検証契約: ADMIN権限を持たないプレイヤーのMCID補完は空候補を返し、API検索を呼び出さない。
     */
    @Test
    void refusesMcidCompletionForNonAdminPlayer() {
        UserService userService = mock(UserService.class);
        Player regularPlayer = mock(Player.class);
        UUID regularUuid = UUID.randomUUID();
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        when(regularPlayer.getUniqueId()).thenReturn(regularUuid);
        when(regularAstPlayer.getBukkit()).thenReturn(regularPlayer);
        when(regularAstPlayer.hasAdminPermission()).thenReturn(false);
        AstPlayerCache.put(regularAstPlayer);

        BanTabCompleter completer = new BanTabCompleter(userService);

        assertTrue(
                completer.onTabCompleteAsync(regularPlayer, null, "ban", new String[] {"Al"})
                        .join()
                        .isEmpty()
        );
        verify(userService, never()).getMcidSuggestions(any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-コマンド.md
     * 章・見出し: # 01_3-コマンド > ## 1. command メソッド仕様 > ### BAN補完候補取得
     * 検証契約: MCID検索が失敗した場合は、直前に取得したキャッシュ候補を返す。
     */
    @Test
    void usesCachedMcidsWhenMcidCompletionFails() {
        UserService userService = mock(UserService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        List<String> cached = List.of("Alice");

        when(userService.getMcidSuggestions("Al"))
                .thenReturn(cached)
                .thenThrow(new IllegalStateException("API unavailable"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BanCommandTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        runSchedulerImmediately(plugin, scheduler);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            BanTabCompleter completer = new BanTabCompleter(userService);

            assertEquals(
                    cached,
                    completer.onTabCompleteAsync(console, null, "ban", new String[] {"Al"}).join()
            );
            assertEquals(
                    cached,
                    completer.onTabCompleteAsync(console, null, "ban", new String[] {"Al"}).join()
            );
        }

        verify(userService, times(2)).getMcidSuggestions("Al");
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
