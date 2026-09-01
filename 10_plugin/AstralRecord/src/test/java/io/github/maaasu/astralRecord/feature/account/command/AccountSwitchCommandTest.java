package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountDeleteResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountSwitchCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントスロット切替
     * 検証契約: 切替開始時にカーソル上のアイテムがある場合、アイテムを消去せずP_5344で拒否する。
     */
    @Test
    void rejectsSwitchWhenCursorContainsItem() {
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        ItemStack cursor = mock(ItemStack.class);
        when(player.getName()).thenReturn("tester");
        when(player.isOnline()).thenReturn(true);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(cursor.getType()).thenReturn(Material.STONE);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(true);

        AstralRecord plugin = mock(AstralRecord.class);
        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayerExact("tester")).thenReturn(player);
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            new AccountSwitchCommand().onCommand(player, null, "account", new String[] {"tester", "1"});
        }

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(messageCaptor.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(messageCaptor.getValue())
            .contains("カーソル上のアイテムを空にしてからアカウントを切り替えてください。"));
        verify(player, never()).setItemOnCursor(any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントスロット切替
     * 検証契約: スロット解決に失敗した場合は、旧GUI編集状態を破棄せず、旧セッション解放も開始しない。
     */
    @Test
    void keepsOldGuiStateWhenSlotResolutionFails() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        Fixture fixture = fixture(player, astPlayer);
        when(fixture.accountService().getAccounts(userId))
            .thenThrow(new IllegalStateException("account lookup failed"));

        runCommand(fixture, player, astPlayer, "1");

        verify(fixture.skillBindGuiEventHandler(), never()).releaseForAccountSwitch(any(Player.class));
        verify(fixture.playerJoinEventHandler(), never()).prepareAccountSwitch(any(Player.class));
        verify(player, never()).closeInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントスロット切替
     * 検証契約: 現在のスロット指定では、GUI編集状態と旧セッションをそのまま維持する。
     */
    @Test
    void keepsOldGuiStateWhenCurrentSlotIsRequested() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        AccountModel current = account(previousAccountId, 0, "slot-0");
        Fixture fixture = fixture(player, astPlayer);
        when(fixture.accountService().getAccounts(userId)).thenReturn(List.of(current));

        runCommand(fixture, player, astPlayer, "0");

        verify(fixture.skillBindGuiEventHandler(), never()).releaseForAccountSwitch(any(Player.class));
        verify(fixture.playerJoinEventHandler(), never()).prepareAccountSwitch(any(Player.class));
        verify(player, never()).closeInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_4-統合フロー.md
     * 章・見出し: # 02_4-統合フロー > ## 2. アカウントスロット切替
     * 検証契約: 作成済みスロットの指定は旧セッション保存完了後にAPI選択先を切り替え、対象アカウントを再ロードする。
     */
    @Test
    void switchesToExistingAccountAfterSavingOldSession() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 1, "slot-1");
        Fixture fixture = fixture(player, astPlayer);
        CompletableFuture<Boolean> logoutSave = CompletableFuture.completedFuture(true);
        when(fixture.accountService().getAccounts(userId)).thenReturn(List.of(
            previous,
            target
        ));
        when(fixture.playerJoinEventHandler().prepareAccountSwitch(player))
            .thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(previousAccountId, logoutSave));
        when(fixture.accountService().switchAccount(userId, targetAccountId, userId)).thenReturn(target);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());

        runCommand(fixture, player, astPlayer, "1");

        verify(fixture.accountService()).switchAccount(userId, targetAccountId, userId);
        verify(fixture.accountService(), never()).createAccount(any(), any(), any(Integer.class), any());
        verify(fixture.playerService()).awaitQueuedSavesForAccountSwitch(
            eq(previousAccountId), same(logoutSave));
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());
        verify(fixture.skillBindGuiEventHandler()).releaseForAccountSwitch(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_4-統合フロー.md
     * 章・見出し: # 02_4-統合フロー > ## 2. アカウントスロット切替
     * 検証契約: 未作成スロットの指定はプレイヤー名で新規アカウントを作成してから、そのアカウントへ切り替える。
     */
    @Test
    void createsMissingSlotBeforeSwitchingToIt() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 3, "slot-3");
        Fixture fixture = fixture(player, astPlayer);
        CompletableFuture<Boolean> logoutSave = CompletableFuture.completedFuture(true);
        when(fixture.accountService().getAccounts(userId)).thenReturn(List.of(
            previous
        ));
        when(fixture.accountService().createAccount(userId, "tester", 3, userId)).thenReturn(target);
        when(fixture.playerJoinEventHandler().prepareAccountSwitch(player))
            .thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(previousAccountId, logoutSave));
        when(fixture.accountService().switchAccount(userId, targetAccountId, userId)).thenReturn(target);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());

        runCommand(fixture, player, astPlayer, "3");

        verify(fixture.accountService()).createAccount(userId, "tester", 3, userId);
        verify(fixture.accountService()).switchAccount(userId, targetAccountId, userId);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());
        verify(fixture.skillBindGuiEventHandler()).releaseForAccountSwitch(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_4-統合フロー.md
     * 章・見出し: # 02_4-統合フロー > ## 2. アカウントスロット切替
     * 検証契約: 作成したスロットへの切替に失敗した場合、旧アカウントを復元したうえで作成アカウントを補償削除する。
     */
    @Test
    void removesCreatedAccountWhenSwitchFails() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 3, "slot-3");
        Fixture fixture = fixture(player, astPlayer);
        CompletableFuture<Boolean> logoutSave = CompletableFuture.completedFuture(true);
        when(fixture.accountService().getAccounts(userId)).thenReturn(List.of(previous));
        when(fixture.accountService().createAccount(userId, "tester", 3, userId)).thenReturn(target);
        when(fixture.playerJoinEventHandler().prepareAccountSwitch(player))
            .thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(previousAccountId, logoutSave));
        when(fixture.accountService().switchAccount(userId, previousAccountId, userId)).thenReturn(previous);
        when(fixture.accountService().deleteAccount(eq(targetAccountId), eq(userId)))
            .thenReturn(mock(AccountDeleteResult.class));
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());

        runCommand(fixture, player, astPlayer, "3");

        verify(fixture.accountService()).switchAccount(userId, previousAccountId, userId);
        verify(fixture.accountService()).deleteAccount(targetAccountId, userId);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_4-統合フロー.md
     * 章・見出し: # 02_4-統合フロー > ## 2. アカウントスロット切替
     * 検証契約: 旧セッション保存に失敗した場合は新アカウントへ切り替えず、旧アカウントを復元して再ロードする。
     */
    @Test
    void recoversPreviousAccountWhenOldSessionSaveFails() {
        UUID userId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player player = player(userId);
        AstPlayer astPlayer = astPlayer(userId, previousAccountId);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 1, "slot-1");
        Fixture fixture = fixture(player, astPlayer);
        CompletableFuture<Boolean> logoutSave = CompletableFuture.completedFuture(false);
        when(fixture.accountService().getAccounts(userId)).thenReturn(List.of(previous, target));
        when(fixture.playerJoinEventHandler().prepareAccountSwitch(player))
            .thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(previousAccountId, logoutSave));
        doThrow(new IllegalStateException("logout save failed"))
            .when(fixture.playerService())
            .awaitQueuedSavesForAccountSwitch(previousAccountId, logoutSave);
        when(fixture.accountService().switchAccount(userId, previousAccountId, userId)).thenReturn(previous);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());

        runCommand(fixture, player, astPlayer, "1");

        verify(fixture.accountService(), never()).switchAccount(userId, targetAccountId, userId);
        verify(fixture.accountService()).switchAccount(userId, previousAccountId, userId);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 2. コマンド仕様
     * 検証契約: account の第一引数は既存の mode/delete に加え switch サブコマンドであり、ルートは管理者権限を要求する。
     */
    @Test
    void keepsAccountAdminSubcommandFormat() {
        AccountCommand command = new AccountCommand();

        assertEquals(UserPermission.ADMIN.getValue(), command.getRequiredPermissionLevel());
        assertEquals("/account <mode|delete|switch> ...", command.getUsage());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントスロット切替
     * 検証契約: 管理者は自分とは別のオンラインプレイヤーを対象に切り替え、API更新者には管理者 UUID を渡す。
     */
    @Test
    void adminCanSwitchAnotherOnlinePlayer() {
        UUID adminId = UUID.randomUUID();
        UUID targetPlayerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player admin = player(adminId, "admin");
        Player targetPlayer = player(targetPlayerId, "tester");
        AstPlayer adminAstPlayer = astPlayer(adminId, UUID.randomUUID());
        AstPlayer targetAstPlayer = astPlayer(targetUserId, previousAccountId);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 1, "slot-1");
        Fixture fixture = fixture(targetPlayer, targetAstPlayer);
        CompletableFuture<Boolean> logoutSave = CompletableFuture.completedFuture(true);
        when(fixture.accountService().getAccounts(targetUserId)).thenReturn(List.of(previous, target));
        when(fixture.playerJoinEventHandler().prepareAccountSwitch(targetPlayer))
            .thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(previousAccountId, logoutSave));
        when(fixture.accountService().switchAccount(targetUserId, targetAccountId, adminId)).thenReturn(target);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(targetPlayer), eq(target), any());

        runCommandAs(fixture, admin, adminAstPlayer, targetPlayer, targetAstPlayer, "1");

        verify(fixture.accountService()).switchAccount(targetUserId, targetAccountId, adminId);
        verify(fixture.playerJoinEventHandler()).prepareAccountSwitch(targetPlayer);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(targetPlayer), eq(target), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント補完候補取得
     * 検証契約: 管理者の switch 補完は対象プレイヤーのキャッシュ済みスロットだけを返し、未作成スロットを候補に含めない。
     */
    @Test
    void completesOnlyCachedSlotsForAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        Player admin = player(adminId, "admin");
        Player target = player(UUID.randomUUID(), "tester");
        AstPlayer adminAstPlayer = astPlayer(adminId, UUID.randomUUID());
        AstPlayer targetAstPlayer = astPlayer(targetUserId, UUID.randomUUID());
        AstralRecord plugin = mock(AstralRecord.class);
        AccountService accountService = mock(AccountService.class);
        when(plugin.getAccountService()).thenReturn(accountService);
        when(accountService.getCachedSlotIndexes(targetUserId)).thenReturn(List.of(0, 2));

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(target));
            bukkit.when(() -> Bukkit.getPlayerExact("tester")).thenReturn(target);
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(adminAstPlayer);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);

            assertEquals(
                List.of("0", "2"),
                new AccountTabCompleter().onTabComplete(
                    admin,
                    null,
                    "account",
                    new String[] {"switch", "tester", ""}
                )
            );
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_4-統合フロー.md
     * 章・見出し: # 02_4-統合フロー > ## 2. アカウントスロット切替
     * 検証契約: オフライン対象は API のユーザー解決後に未作成スロットを作成し、実行者を更新者として選択状態を更新する。再ロードは行わない。
     */
    @Test
    void switchesOfflinePlayerAndCreatesMissingSlot() {
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        UUID previousAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        Player admin = player(adminId, "admin");
        AstPlayer adminAstPlayer = astPlayer(adminId, UUID.randomUUID());
        UserModel targetUser = mock(UserModel.class);
        AccountModel previous = account(previousAccountId, 0, "slot-0");
        AccountModel target = account(targetAccountId, 4, "slot-4");
        Fixture fixture = fixture(admin, adminAstPlayer);
        when(targetUser.getUuid()).thenReturn(targetUserId);
        when(targetUser.getMcid()).thenReturn("offline");
        when(targetUser.getAccountId()).thenReturn(previousAccountId);
        when(fixture.userService().getUserByMcid("offline")).thenReturn(targetUser);
        when(fixture.accountService().getAccounts(targetUserId)).thenReturn(List.of(previous));
        when(fixture.accountService().createAccount(targetUserId, "offline", 4, adminId)).thenReturn(target);
        when(fixture.accountService().switchAccount(targetUserId, targetAccountId, adminId)).thenReturn(target);

        runOfflineCommand(fixture, admin, adminAstPlayer, "offline", "4");

        verify(fixture.userService()).getUserByMcid("offline");
        verify(fixture.accountService()).createAccount(targetUserId, "offline", 4, adminId);
        verify(fixture.accountService()).switchAccount(targetUserId, targetAccountId, adminId);
        verify(fixture.playerJoinEventHandler(), never()).prepareAccountSwitch(any(Player.class));
        verify(fixture.playerJoinEventHandler(), never()).reloadAccount(any(Player.class), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント補完候補取得
     * 検証契約: 管理者権限のないプレイヤーには account の補完候補を返さない。
     */
    @Test
    void hidesAccountCompletionFromNonAdmin() {
        Player player = player(UUID.randomUUID(), "tester");
        AstPlayer astPlayer = astPlayer(UUID.randomUUID(), UUID.randomUUID());
        when(astPlayer.hasAdminPermission()).thenReturn(false);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            assertTrue(new AccountTabCompleter().onTabComplete(
                player,
                null,
                "account",
                new String[] {""}
            ).isEmpty());
        }
    }

    private void runCommand(Fixture fixture, Player player, AstPlayer astPlayer, String slot) {
        runCommandAs(fixture, player, astPlayer, player, astPlayer, slot);
    }

    private void runCommandAs(
        Fixture fixture,
        Player sender,
        AstPlayer senderAstPlayer,
        Player target,
        AstPlayer targetAstPlayer,
        String slot
    ) {
        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin());
            bukkit.when(() -> Bukkit.getPlayerExact(target.getName())).thenReturn(target);
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(senderAstPlayer);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(fixture.messageService());
            new AccountSwitchCommand().onCommand(
                sender,
                null,
                "account",
                new String[] {target.getName(), slot}
            );
        }
    }

    private void runOfflineCommand(
        Fixture fixture,
        Player sender,
        AstPlayer senderAstPlayer,
        String targetName,
        String slot
    ) {
        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin());
            bukkit.when(() -> Bukkit.getPlayerExact(targetName)).thenReturn(null);
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(senderAstPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(fixture.messageService());
            new AccountSwitchCommand().onCommand(
                sender,
                null,
                "account",
                new String[] {targetName, slot}
            );
        }
    }

    private Fixture fixture(Player player, AstPlayer astPlayer) {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AccountService accountService = mock(AccountService.class);
        UserService userService = mock(UserService.class);
        PlayerService playerService = mock(PlayerService.class);
        PlayerJoinEventHandler playerJoinEventHandler = mock(PlayerJoinEventHandler.class);
        SkillBindGuiEventHandler skillBindGuiEventHandler = mock(SkillBindGuiEventHandler.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getAccountService()).thenReturn(accountService);
        when(plugin.getUserService()).thenReturn(userService);
        when(plugin.getPlayerService()).thenReturn(playerService);
        when(plugin.getPlayerJoinEventHandler()).thenReturn(playerJoinEventHandler);
        when(plugin.getSkillBindGuiEventHandler()).thenReturn(skillBindGuiEventHandler);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        });
        return new Fixture(
            plugin,
            accountService,
            userService,
            playerService,
            playerJoinEventHandler,
            skillBindGuiEventHandler,
            messageService,
            player,
            astPlayer
        );
    }

    private Player player(UUID playerId) {
        return player(playerId, "tester");
    }

    private Player player(UUID playerId, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getItemOnCursor()).thenReturn(null);
        return player;
    }

    private AstPlayer astPlayer(UUID userId, UUID accountId) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        io.github.maaasu.astralRecord.feature.user.model.UserModel user =
            mock(io.github.maaasu.astralRecord.feature.user.model.UserModel.class);
        AccountModel account = account(accountId, 0, "slot-0");
        when(user.getUuid()).thenReturn(userId);
        when(astPlayer.getUser()).thenReturn(user);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(true);
        return astPlayer;
    }

    private AccountModel account(UUID accountId, int slotIndex, String name) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getSlotIndex()).thenReturn(slotIndex);
        when(account.getAccountName()).thenReturn(name);
        return account;
    }

    private record Fixture(
        AstralRecord plugin,
        AccountService accountService,
        UserService userService,
        PlayerService playerService,
        PlayerJoinEventHandler playerJoinEventHandler,
        SkillBindGuiEventHandler skillBindGuiEventHandler,
        PlayerMessageService messageService,
        Player player,
        AstPlayer astPlayer
    ) {
    }
}
