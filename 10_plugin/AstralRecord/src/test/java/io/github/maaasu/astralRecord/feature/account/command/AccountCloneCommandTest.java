package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountManagementRepository;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** サーバーを起動せず、破壊的な上書き操作の確認境界を検証します。 */
class AccountCloneCommandTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: onlineの両アカウントを凍結して保存完了を待ち、複製と再読込後だけ操作受付を再開する。
     */
    @Test
    void onlineCloneWaitsForBothSavesAndReleasesOnlyAfterReload() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.online();
            fixture.request();
            fixture.command.confirm(fixture.sender, new String[] {fixture.confirmationToken()});
            fixture.assertFrozen(fixture.sourcePlayer, true);
            fixture.assertFrozen(fixture.targetPlayer, true);
            CompletableFuture<Void> worker = CompletableFuture.runAsync(fixture.async.remove());
            fixture.sourceSaved.complete(true);
            assertTrue(fixture.targetSaveWait.await(10, TimeUnit.SECONDS));
            verify(fixture.repository, never()).cloneAccount(any(), any(), anyInt(), any(), any());
            fixture.targetSaved.complete(true);
            worker.get(10, TimeUnit.SECONDS);
            fixture.drain();
            var order = inOrder(fixture.join, fixture.players, fixture.repository);
            order.verify(fixture.join, times(2)).prepareAccountSwitch(any());
            order.verify(fixture.players, times(2)).awaitQueuedSavesForAccountSwitch(any(), any());
            order.verify(fixture.repository).cloneAccount(fixture.sourceId, fixture.targetUserId, 2,
                fixture.existingId, SystemUser.INSTANCE.getUuid());
            order.verify(fixture.join, times(2)).reloadAccount(any(), any(), any());
            fixture.assertFrozen(fixture.sourcePlayer, false);
            fixture.assertFrozen(fixture.targetPlayer, false);
            assertNull(fixture.guard.current(fixture.sourceUserId));
            assertNull(fixture.guard.current(fixture.targetUserId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 複製APIが失敗しても切り離した両セッションを選択中アカウントへ復旧する。
     */
    @Test
    void cloneFailureReloadsBothOriginalAccountsAndReleasesGuards() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.online();
            fixture.sourceSaved.complete(true);
            fixture.targetSaved.complete(true);
            when(fixture.repository.cloneAccount(any(), any(), anyInt(), any(), any()))
                .thenThrow(new java.io.IOException("Unavailable"));
            fixture.request();
            fixture.command.confirm(fixture.sender, new String[] {fixture.confirmationToken()});
            fixture.drain();
            verify(fixture.join).reloadAccount(eq(fixture.sourcePlayer), eq(fixture.source), any());
            verify(fixture.join).reloadAccount(eq(fixture.targetPlayer), eq(fixture.target), any());
            verify(fixture.sourcePlayer, never()).kick(any(net.kyori.adventure.text.Component.class));
            verify(fixture.targetPlayer, never()).kick(any(net.kyori.adventure.text.Component.class));
            assertNull(fixture.guard.current(fixture.sourceUserId));
            assertNull(fixture.guard.current(fixture.targetUserId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 保存に失敗したときは複製APIを呼ばず、復旧不能なセッションを切断して遷移を解放する。
     */
    @Test
    void failedLogoutSavePreventsCloneAndReleasesTheSessionGuard() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.online();
            fixture.sourceSaved.complete(false);
            fixture.targetSaved.complete(true);
            fixture.request();
            fixture.command.confirm(fixture.sender, new String[] {fixture.confirmationToken()});
            fixture.drain();
            verify(fixture.repository, never()).cloneAccount(any(), any(), anyInt(), any(), any());
            verify(fixture.sourcePlayer).kick(any(net.kyori.adventure.text.Component.class));
            assertNull(fixture.guard.current(fixture.sourceUserId));
            assertNull(fixture.guard.current(fixture.targetUserId));
            fixture.assertFrozen(fixture.sourcePlayer, false);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 既存先は確認前に書き込まず、確認を発行した実行者だけが一度だけ上書きできる。
     */
    @Test
    void overwriteRequiresOriginalSenderAndConsumesConfirmationOnlyOnce() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.request();
            String token = fixture.confirmationToken();
            verify(fixture.repository, never()).cloneAccount(any(), any(), anyInt(), any(), any());

            fixture.command.confirm(mock(CommandSender.class), new String[] {token});
            fixture.drain();
            verify(fixture.repository, never()).cloneAccount(any(), any(), anyInt(), any(), any());

            fixture.command.confirm(fixture.sender, new String[] {token});
            fixture.drain();
            fixture.command.confirm(fixture.sender, new String[] {token});
            fixture.drain();
            verify(fixture.repository, times(1)).cloneAccount(fixture.sourceId, fixture.targetUserId, 2,
                fixture.existingId, SystemUser.INSTANCE.getUuid());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 確認の有効期限2分を迎えたトークンでは上書きAPIを呼び出さない。
     */
    @Test
    void expiredConfirmationCannotOverwrite() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.request();
            String token = fixture.confirmationToken();
            fixture.clock.set(120_000_000_000L);
            fixture.command.confirm(fixture.sender, new String[] {token});
            fixture.drain();
            verify(fixture.repository, never()).cloneAccount(any(), any(), anyInt(), any(), any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 未作成先は確認なしで複製するが、既存先の上書きを許可するUUIDは送信しない。
     */
    @Test
    void emptyDestinationDoesNotAuthorizeOverwritingAnAccountCreatedConcurrently() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.request();
            verify(fixture.repository).cloneAccount(fixture.sourceId, fixture.targetUserId, 2,
                null, SystemUser.INSTANCE.getUuid());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント複製
     * 検証契約: 複製先の負値・100以上はAPI照会や複製を開始する前に拒否する。
     */
    @Test
    void invalidDestinationSlotsNeverReachTheApi() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            for (String slot : List.of("-1", "100", "999999999999"))
                fixture.command.executeCommand(fixture.sender, new String[] {fixture.sourceId.toString(), "Target", slot});
            fixture.drain();
            verifyNoInteractions(fixture.repository);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final UUID sourceId = UUID.randomUUID();
        private final UUID sourceUserId = UUID.randomUUID();
        private final UUID targetUserId = UUID.randomUUID();
        private final UUID existingId = UUID.randomUUID();
        private final AccountManagementRepository repository = mock(AccountManagementRepository.class);
        private final AtomicLong clock = new AtomicLong();
        private final CommandSender sender = mock(CommandSender.class);
        private final List<String> messages = new ArrayList<>();
        private final ConcurrentLinkedQueue<Runnable> async = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Runnable> sync = new ConcurrentLinkedQueue<>();
        private final MockedStatic<AstralRecord> pluginStatic = mockStatic(AstralRecord.class);
        private final MockedStatic<Bukkit> bukkitStatic = mockStatic(Bukkit.class);
        private final MockedStatic<PlayerMessageService> messageStatic = mockStatic(PlayerMessageService.class);
        private final MockedStatic<AstPlayerCache> cacheStatic = mockStatic(AstPlayerCache.class);
        private final MockedStatic<Logger> logStatic = mockStatic(Logger.class);
        private final AccountCloneCommand command = new AccountCloneCommand(repository, clock::get);
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final PlayerJoinEventHandler join = mock(PlayerJoinEventHandler.class);
        private final PlayerService players = mock(PlayerService.class);
        private final PlayerSessionTransitionGuard guard = new PlayerSessionTransitionGuard();
        private final Player sourcePlayer = mock(Player.class);
        private final Player targetPlayer = mock(Player.class);
        private final CompletableFuture<Boolean> sourceSaved = new CompletableFuture<>();
        private final CompletableFuture<Boolean> targetSaved = new CompletableFuture<>();
        private final CountDownLatch targetSaveWait = new CountDownLatch(1);
        private final AccountModel source = account(sourceId, sourceUserId, "Source", 0);
        private final AccountModel target = account(existingId, targetUserId, "Existing", 2);
        private final AtomicReference<AccountModel> selectedTarget = new AtomicReference<>(target);

        private Fixture(boolean occupied) throws Exception {
            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            UserService users = mock(UserService.class);
            AccountService accounts = mock(AccountService.class);
            PlayerMessageService output = mock(PlayerMessageService.class);
            UserModel targetUser = mock(UserModel.class);
            AccountModel copied = account(UUID.randomUUID(), targetUserId, "Target", 2);
            when(sender.getName()).thenReturn("Console");
            when(targetUser.getUuid()).thenReturn(targetUserId);
            when(users.getUserByMcid("Target")).thenReturn(targetUser);
            when(accounts.getAccounts(targetUserId)).thenReturn(occupied ? List.of(target) : List.of());
            when(repository.resolve(sourceId.toString(), null)).thenReturn(source);
            when(repository.cloneAccount(eq(sourceId), eq(targetUserId), eq(2), any(), any())).thenAnswer(call -> {
                selectedTarget.set(copied); return copied;
            });
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getUserService()).thenReturn(users);
            when(plugin.getAccountService()).thenReturn(accounts);
            when(plugin.getPlayerService()).thenReturn(players);
            when(plugin.getPlayerJoinEventHandler()).thenReturn(join);
            when(plugin.getPlayerSessionTransitionGuard()).thenReturn(guard);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                async.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                sync.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            doAnswer(call -> { messages.add(call.getArgument(1)); return null; })
                .when(output).sendRaw(eq(sender), anyString());
            pluginStatic.when(AstralRecord::getInstance).thenReturn(plugin);
            messageStatic.when(PlayerMessageService::getInstance).thenReturn(output);
        }

        private void online() throws Exception {
            configurePlayer(sourcePlayer, sourceUserId, "SourceUser", source);
            configurePlayer(targetPlayer, targetUserId, "Target", target);
            bukkitStatic.when(() -> Bukkit.getPlayer(sourceUserId)).thenReturn(sourcePlayer);
            bukkitStatic.when(() -> Bukkit.getPlayer(targetUserId)).thenReturn(targetPlayer);
            when(join.prepareAccountSwitch(sourcePlayer)).thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(sourceId, sourceSaved));
            when(join.prepareAccountSwitch(targetPlayer)).thenReturn(new PlayerJoinEventHandler.AccountSwitchPreparation(existingId, targetSaved));
            when(repository.resolve(null, "SourceUser")).thenReturn(source);
            when(repository.resolve(null, "Target")).thenAnswer(call -> selectedTarget.get());
            doAnswer(call -> {
                if (call.getArgument(0).equals(existingId)) targetSaveWait.countDown();
                CompletableFuture<Boolean> saved = call.getArgument(1);
                if (!Boolean.TRUE.equals(saved.join())) throw new IllegalStateException("Save failed");
                return null;
            }).when(players).awaitQueuedSavesForAccountSwitch(any(), any());
            doAnswer(call -> {
                Consumer<Boolean> completion = call.getArgument(2);
                completion.accept(true); return null;
            }).when(join).reloadAccount(any(), any(), any());
        }

        private void configurePlayer(Player player, UUID id, String name, AccountModel account) {
            AstPlayer ast = mock(AstPlayer.class);
            ItemStack cursor = mock(ItemStack.class);
            Material air = mock(Material.class);
            when(air.isAir()).thenReturn(true);
            when(cursor.getType()).thenReturn(air);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            when(player.getItemOnCursor()).thenReturn(cursor);
            when(ast.getAccount()).thenReturn(account);
            cacheStatic.when(() -> AstPlayerCache.get(player)).thenReturn(ast);
        }

        private void assertFrozen(Player player, boolean expected) {
            PlayerMoveEvent event = mock(PlayerMoveEvent.class);
            when(event.getPlayer()).thenReturn(player);
            command.onMove(event);
            verify(event, expected ? times(1) : never()).setCancelled(true);
        }

        private void request() {
            command.executeCommand(sender, new String[] {sourceId.toString(), "Target", "2"});
            drain();
        }

        private void drain() {
            int limit = 100;
            while (!async.isEmpty() || !sync.isEmpty()) {
                assertTrue(limit-- > 0, "Scheduler did not finish");
                Runnable work;
                while ((work = async.poll()) != null) work.run();
                while ((work = sync.poll()) != null) work.run();
            }
        }

        private String confirmationToken() {
            var matcher = Pattern.compile("/account confirm ([0-9a-f-]{36})").matcher(String.join("\n", messages));
            assertTrue(matcher.find(), "Confirmation command was not presented");
            return matcher.group(1);
        }

        private static AccountModel account(UUID id, UUID owner, String name, int slot) {
            AccountModel account = mock(AccountModel.class);
            when(account.getUuid()).thenReturn(id);
            when(account.getUserId()).thenReturn(owner);
            when(account.getAccountName()).thenReturn(name);
            when(account.getSlotIndex()).thenReturn(slot);
            return account;
        }

        @Override public void close() {
            logStatic.close(); cacheStatic.close(); messageStatic.close(); bukkitStatic.close(); pluginStatic.close();
        }
    }
}
