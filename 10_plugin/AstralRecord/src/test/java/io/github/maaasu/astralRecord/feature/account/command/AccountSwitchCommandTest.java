package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
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

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
        ItemStack cursor = mock(ItemStack.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(cursor.getType()).thenReturn(Material.STONE);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of());
            new AccountSwitchCommand().onCommand(player, null, "account", new String[] {"1"});
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
        when(fixture.accountService().switchAccount(userId, targetAccountId)).thenReturn(target);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());

        runCommand(fixture, player, astPlayer, "1");

        verify(fixture.accountService()).switchAccount(userId, targetAccountId);
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
        when(fixture.accountService().switchAccount(userId, targetAccountId)).thenReturn(target);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());

        runCommand(fixture, player, astPlayer, "3");

        verify(fixture.accountService()).createAccount(userId, "tester", 3, userId);
        verify(fixture.accountService()).switchAccount(userId, targetAccountId);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(target), any());
        verify(fixture.skillBindGuiEventHandler()).releaseForAccountSwitch(player);
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
        when(fixture.accountService().switchAccount(userId, previousAccountId)).thenReturn(previous);
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());

        runCommand(fixture, player, astPlayer, "1");

        verify(fixture.accountService(), never()).switchAccount(userId, targetAccountId);
        verify(fixture.accountService()).switchAccount(userId, previousAccountId);
        verify(fixture.playerJoinEventHandler()).reloadAccount(eq(player), eq(previous), any());
    }

    private void runCommand(Fixture fixture, Player player, AstPlayer astPlayer, String slot) {
        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin());
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(fixture.messageService());
            new AccountSwitchCommand().onCommand(player, null, "account", new String[] {slot});
        }
    }

    private Fixture fixture(Player player, AstPlayer astPlayer) {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AccountService accountService = mock(AccountService.class);
        PlayerService playerService = mock(PlayerService.class);
        PlayerJoinEventHandler playerJoinEventHandler = mock(PlayerJoinEventHandler.class);
        SkillBindGuiEventHandler skillBindGuiEventHandler = mock(SkillBindGuiEventHandler.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getAccountService()).thenReturn(accountService);
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
            playerService,
            playerJoinEventHandler,
            skillBindGuiEventHandler,
            messageService,
            player,
            astPlayer
        );
    }

    private Player player(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("tester");
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
        PlayerService playerService,
        PlayerJoinEventHandler playerJoinEventHandler,
        SkillBindGuiEventHandler skillBindGuiEventHandler,
        PlayerMessageService messageService,
        Player player,
        AstPlayer astPlayer
    ) {
    }
}
