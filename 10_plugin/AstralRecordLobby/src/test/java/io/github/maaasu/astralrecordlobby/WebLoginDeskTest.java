package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 保有確認・作成・再接続の境界を、API通信件数と通知で検証します。 */
class WebLoginDeskTest {
    @Test
    void displaysVisibleCodeAndLoginUrlWithDistinctClickActions() {
        Fixture f = new Fixture();
        when(f.player.isOnline()).thenReturn(true);
        new PlayerMessageService().sendWebLoginCode(f.player, f.code);

        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(f.player).sendMessage(message.capture());
        List<TextComponent> parts = StreamSupport.stream(
            message.getValue().iterable(ComponentIteratorType.DEPTH_FIRST).spliterator(), false)
            .filter(TextComponent.class::isInstance).map(TextComponent.class::cast).toList();
        assertTrue(parts.stream().anyMatch(part -> f.code.code().equals(part.content())
            && ClickEvent.copyToClipboard(f.code.code()).equals(part.clickEvent())));
        String loginUrl = f.code.loginUrl().toString();
        assertTrue(parts.stream().anyMatch(part -> loginUrl.equals(part.content())
            && ClickEvent.openUrl(loginUrl).equals(part.clickEvent())));
        assertTrue(parts.stream().anyMatch(part -> part.content().contains("09:00:00 JST")));
    }

    @Test
    void existingOwnerOnlyIssuesAndReceivesCodeEvenWithRepeatedClick() {
        Fixture f = new Fixture();
        var existing = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "AlreadyOwned");
        when(f.api.getAccounts(f.id)).thenReturn(List.of(existing,
            new LobbyApiClient.AccountSummary(UUID.randomUUID(), "Second")));
        f.joinAndLoad();
        clearInvocations(f.api);

        f.creator.requestLoginCode(f.player);
        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        verify(f.api).issueWebLoginCode(f.id, "MinecraftName", "lobby");
        verifyNoMoreInteractions(f.api);
        verify(f.messages).sendWebLoginCode(f.player, f.code);
    }

    @Test
    void refusesLoadingAndFailedSnapshotsWithoutRetryingOnClick() {
        Fixture f = new Fixture();
        when(f.api.getAccounts(f.id)).thenThrow(new IllegalStateException("unavailable"));
        f.creator.onJoin(f.player);
        f.creator.requestLoginCode(f.player);
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_LOADING);
        verifyNoInteractions(f.api);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.requestLoginCode(f.player);
        f.creator.requestLoginCode(f.player);

        verify(f.api, times(1)).getAccounts(f.id);
        verify(f.api, never()).createAccount(any(), any());
        verify(f.messages, times(2)).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void createsAndSelectsFirstAccountBeforeIssuingCode() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName2");
        when(f.api.createAccount(f.id, "MinecraftName")).thenReturn(created);
        f.joinAndLoad();

        f.creator.requestLoginCode(f.player);
        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        var order = inOrder(f.api);
        order.verify(f.api).getAccounts(f.id);
        order.verify(f.api).ensureUser(f.id, "MinecraftName", "");
        order.verify(f.api).createAccount(f.id, "MinecraftName");
        order.verify(f.api).selectAccount(f.id, created.uuid());
        order.verify(f.api).issueWebLoginCode(f.id, "MinecraftName", "lobby");
        verifyNoMoreInteractions(f.api);
        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATED, "MinecraftName2");
        verify(f.messages).sendWebLoginCode(f.player, f.code);
    }

    @Test
    void cachesCreatedAccountEvenIfSelectingItFails() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName");
        when(f.api.createAccount(any(), any())).thenReturn(created);
        doThrow(new IllegalStateException("selection failed")).when(f.api).selectAccount(any(), any());
        f.joinAndLoad();
        f.creator.requestLoginCode(f.player);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATED_SELECTION_FAILED, "MinecraftName");
        verify(f.messages, times(2)).sendWebLoginCode(f.player, f.code);
    }

    @Test
    void retryAfterIssueFailureDoesNotCreateAgain() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName");
        when(f.api.createAccount(any(), any())).thenReturn(created);
        when(f.api.issueWebLoginCode(f.id, "MinecraftName", "lobby"))
            .thenThrow(new IllegalStateException("unavailable")).thenReturn(f.code);
        f.joinAndLoad();

        f.creator.requestLoginCode(f.player);
        f.completeRequest();
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_CREATED, "MinecraftName");
        verify(f.messages).send(f.player, PlayerMsgId.WEB_LOGIN_CODE_FAILED);
        verify(f.messages, never()).sendWebLoginCode(any(), any());

        f.creator.requestLoginCode(f.player);
        f.completeRequest();
        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.api, times(2)).issueWebLoginCode(f.id, "MinecraftName", "lobby");
        verify(f.messages).sendWebLoginCode(f.player, f.code);
    }

    @Test
    void unknownWriteOutcomeBlocksFurtherWritesUntilRejoin() {
        Fixture f = new Fixture();
        when(f.api.createAccount(any(), any())).thenThrow(new IllegalStateException("timeout"));
        f.joinAndLoad();
        f.creator.requestLoginCode(f.player);
        f.async.remove().run();
        f.sync.remove().run();

        f.creator.requestLoginCode(f.player);

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.api, never()).selectAccount(any(), any());
        verify(f.api, never()).issueWebLoginCode(any(), any(), any());
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

        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        verify(f.messages).sendWebLoginCode(f.player, f.code);
        verify(f.api, never()).createAccount(any(), any());
    }

    @Test
    void retainsWriteGuardAcrossRejoinAndDiscardsLateEmptySnapshot() {
        Fixture f = new Fixture();
        var created = new LobbyApiClient.AccountSummary(UUID.randomUUID(), "MinecraftName");
        when(f.api.createAccount(any(), any())).thenReturn(created);
        f.joinAndLoad();
        f.creator.requestLoginCode(f.player);
        Runnable write = f.async.remove();
        f.creator.onQuit(f.id);
        f.creator.onJoin(f.player);
        f.async.remove().run(); // 新接続がまだ空の一覧を取得する。
        Runnable empty = f.sync.remove();
        f.creator.requestLoginCode(f.player);
        write.run();
        f.sync.remove().run();
        empty.run();
        verify(f.messages, never()).sendWebLoginCode(any(), any());

        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).sendWebLoginCode(f.player, f.code);
    }

    @Test
    void previousWriteTimeoutDoesNotEraseAlreadyLoadedRejoinOwnership() {
        verifyRejoinOwnershipSurvivesOldFailure(true);
    }

    @Test
    void previousWriteTimeoutDoesNotDiscardPendingRejoinOwnership() {
        verifyRejoinOwnershipSurvivesOldFailure(false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ignoresEmptyRejoinSnapshotThatOverlapsAnUnconfirmedWrite(boolean listCompletesFirst) {
        Fixture f = new Fixture();
        when(f.api.createAccount(any(), any())).thenThrow(new IllegalStateException("response lost after commit"));
        f.joinAndLoad();
        f.creator.requestLoginCode(f.player);
        Runnable write = f.async.remove();
        f.creator.onQuit(f.id);
        f.creator.onJoin(f.player);
        f.async.remove().run();
        Runnable listCompletion = f.sync.remove();
        if (listCompletesFirst) listCompletion.run();
        write.run();
        f.sync.remove().run();
        if (!listCompletesFirst) listCompletion.run();

        f.creator.requestLoginCode(f.player);

        assertTrue(f.async.isEmpty());
        verify(f.api).createAccount(f.id, "MinecraftName");
        verify(f.api, never()).issueWebLoginCode(any(), any(), any());
        verify(f.messages).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    private static void verifyRejoinOwnershipSurvivesOldFailure(boolean listCompletesFirst) {
        Fixture f = new Fixture();
        when(f.api.createAccount(any(), any())).thenThrow(new IllegalStateException("response lost after commit"));
        f.joinAndLoad();
        f.creator.requestLoginCode(f.player);
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

        f.creator.requestLoginCode(f.player);
        f.completeRequest();

        verify(f.api, times(1)).createAccount(any(), any());
        verify(f.messages).sendWebLoginCode(f.player, f.code);
        verify(f.messages, never()).send(f.player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final LobbyApiClient.WebLoginCode code = new LobbyApiClient.WebLoginCode(
            "ABCD-EFGH", Instant.parse("2099-01-01T00:00:00Z"), URI.create("https://example.com/Login"));
        final AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final LobbyApiClient api = mock(LobbyApiClient.class);
        final Player player = mock(Player.class);
        final PlayerMessageService messages = mock(PlayerMessageService.class);
        final ArrayDeque<Runnable> async = new ArrayDeque<>();
        final ArrayDeque<Runnable> sync = new ArrayDeque<>();
        final WebLoginDesk creator;

        Fixture() {
            YamlConfiguration config = new YamlConfiguration();
            config.set("accountCreator.npc.enabled", true);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(plugin.api()).thenReturn(api);
            when(plugin.serverId()).thenReturn("lobby");
            when(server.getScheduler()).thenReturn(scheduler);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("MinecraftName");
            when(api.getAccounts(id)).thenReturn(List.of());
            when(api.issueWebLoginCode(id, "MinecraftName", "lobby")).thenReturn(code);
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                async.add(call.getArgument(1));
                return null;
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                sync.add(call.getArgument(1));
                return null;
            });
            creator = new WebLoginDesk(plugin, messages);
        }

        void joinAndLoad() {
            creator.onJoin(player);
            completeRequest();
        }

        void completeRequest() {
            async.remove().run();
            sync.remove().run();
        }
    }
}
