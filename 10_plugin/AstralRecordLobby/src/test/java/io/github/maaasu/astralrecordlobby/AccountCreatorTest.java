package io.github.maaasu.astralrecordlobby;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 保有確認・作成・再接続の境界を、API通信件数と通知で検証します。 */
class AccountCreatorTest {
    @Test
    void rejectsAnyExistingAccountFromJoinCacheWithoutAdditionalRequests() {
        Fixture f = new Fixture();
        var existing = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "AlreadyOwned");
        when(f.api.getAccounts(f.id)).thenReturn(List.of(existing,
            new LobbyApiClient.AccountSummary(UUID.randomUUID(), "Second")));
        f.joinAndLoad();
        clearInvocations(f.api);

        f.creator.create(f.player);
        f.creator.create(f.player);

        verifyNoInteractions(f.api);
        verify(f.messages, times(2)).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "AlreadyOwned");
    }

    @Test
    void refusesLoadingAndFailedSnapshotsWithoutRetryingOnClick() {
        Fixture f = new Fixture();
        when(f.api.getAccounts(f.id)).thenThrow(new IllegalStateException("unavailable"));
        f.creator.onJoin(f.player);
        f.creator.create(f.player);
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_LOADING);
        verifyNoInteractions(f.api);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.create(f.player);
        f.creator.create(f.player);

        verify(f.api, times(1)).getAccounts(f.id);
        verify(f.api, never()).createAccount(any(), any());
        verify(f.messages, times(2)).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void createsOnceWithMinecraftNameAndImmediatelyCachesApiGeneratedName() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName2");
        when(f.api.createAccount(f.id, "MinecraftName")).thenReturn(created);
        f.joinAndLoad();

        f.creator.create(f.player);
        f.creator.create(f.player);
        f.async.remove().run();
        f.sync.remove().run();
        f.creator.create(f.player);

        var order = inOrder(f.api);
        order.verify(f.api).getAccounts(f.id);
        order.verify(f.api).ensureUser(f.id, "MinecraftName", "");
        order.verify(f.api).createAccount(f.id, "MinecraftName");
        order.verify(f.api).selectAccount(f.id, created.uuid());
        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATED, "MinecraftName2");
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "MinecraftName2");
    }

    @Test
    void cachesCreatedAccountEvenIfSelectingItFails() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName");
        when(f.api.createAccount(any(), any())).thenReturn(created);
        doThrow(new IllegalStateException("selection failed")).when(f.api).selectAccount(any(), any());
        f.joinAndLoad();
        f.creator.create(f.player);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.create(f.player);

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATED_SELECTION_FAILED, "MinecraftName");
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "MinecraftName");
    }

    @Test
    void unknownWriteOutcomeBlocksFurtherWritesUntilRejoin() {
        Fixture f = new Fixture();
        when(f.api.createAccount(any(), any())).thenThrow(new IllegalStateException("timeout"));
        f.joinAndLoad();
        f.creator.create(f.player);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.create(f.player);

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.api, never()).selectAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATE_FAILED);
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void ignoresPreviousConnectionsAccountListAfterRejoin() {
        Fixture f = new Fixture();
        var owned = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "OwnedOnNewJoin");
        when(f.api.getAccounts(f.id)).thenReturn(List.of()).thenReturn(List.of(owned));
        f.creator.onJoin(f.player);
        f.async.remove().run(); // 旧接続の空一覧を取得、メインスレッドへの反映は保留。
        Runnable stale = f.sync.remove();
        f.creator.onQuit(f.id);
        f.joinAndLoad();
        stale.run();

        f.creator.create(f.player);

        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "OwnedOnNewJoin");
        verify(f.api, never()).createAccount(any(), any());
    }

    @Test
    void retainsWriteGuardAcrossRejoinAndDiscardsLateEmptySnapshot() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName");
        when(f.api.createAccount(any(), any())).thenReturn(created);
        f.joinAndLoad();
        f.creator.create(f.player);
        Runnable write = f.async.remove();
        f.creator.onQuit(f.id);
        f.creator.onJoin(f.player);
        f.async.remove().run(); // 新接続がまだ空の一覧を取得する。
        Runnable empty = f.sync.remove();
        f.creator.create(f.player);
        write.run();
        f.sync.remove().run();
        empty.run();

        f.creator.create(f.player);

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "MinecraftName");
    }

    @Test
    void previousWriteTimeoutDoesNotEraseAlreadyLoadedRejoinOwnership() {
        verifyRejoinOwnershipSurvivesOldFailure(true);
    }

    @Test
    void previousWriteTimeoutDoesNotDiscardPendingRejoinOwnership() {
        verifyRejoinOwnershipSurvivesOldFailure(false);
    }

    private static void verifyRejoinOwnershipSurvivesOldFailure(boolean listCompletesFirst) {
        Fixture f = new Fixture();
        when(f.api.createAccount(any(), any())).thenThrow(new IllegalStateException("response lost after commit"));
        f.joinAndLoad();
        f.creator.create(f.player);
        Runnable write = f.async.remove();
        f.creator.onQuit(f.id);
        var owned = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "CommittedAccount");
        when(f.api.getAccounts(f.id)).thenReturn(List.of(owned));
        f.creator.onJoin(f.player);
        f.async.remove().run();
        Runnable listCompletion = f.sync.remove();
        write.run();
        Runnable failureCompletion = f.sync.remove();
        if (listCompletesFirst) {
            listCompletion.run();
            failureCompletion.run();
        } else {
            failureCompletion.run();
            listCompletion.run();
        }

        f.creator.create(f.player);

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, "CommittedAccount");
        verify(f.messages, never()).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final LobbyApiClient api = mock(LobbyApiClient.class);
        final Player player = mock(Player.class);
        final PlayerMessageService messages = mock(PlayerMessageService.class);
        final ArrayDeque<Runnable> async = new ArrayDeque<>();
        final ArrayDeque<Runnable> sync = new ArrayDeque<>();
        final AccountCreator creator;

        Fixture() {
            YamlConfiguration config = new YamlConfiguration();
            config.set("accountCreator.npc.enabled", true);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(plugin.api()).thenReturn(api);
            when(server.getScheduler()).thenReturn(scheduler);
            when(server.getPlayer(id)).thenReturn(player);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("MinecraftName");
            when(api.getAccounts(id)).thenReturn(List.of());
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                async.add(call.getArgument(1));
                return null;
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                sync.add(call.getArgument(1));
                return null;
            });
            creator = new AccountCreator(plugin, messages);
        }

        void joinAndLoad() {
            creator.onJoin(player);
            async.remove().run();
            sync.remove().run();
        }
    }
}
