package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkBridgeServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: RPGの職業shortNameに定義された色・装飾コードをProxy Tab向けmetadataで保持する。
     */
    @Test
    void preservesShortNameStyleForProxyTabMetadata() {
        assertEquals("§dMAG", NetworkBridgeService.tabClassName("&dMAG", "mage"));
        assertEquals("§c§lADM", NetworkBridgeService.tabClassName("&c&lADM", "admin"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: network有効時はRPG側のTab更新を停止し、停止時に更新を再有効化する。
     */
    @Test
    void disablesRpgTabUpdatesWhileNetworkIsEnabledAndRestoresThemOnStop() {
        Fixture fixture = new Fixture(true);

        fixture.service.start();
        verify(fixture.playerClassService).setPlayerListNameUpdatesEnabled(false);

        fixture.service.stop();
        verify(fixture.playerClassService).setPlayerListNameUpdatesEnabled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: network無効時はRPG側のTab更新を有効にしたままにする。
     */
    @Test
    void keepsRpgTabUpdatesEnabledWhenNetworkIsDisabled() {
        Fixture fixture = new Fixture(false);

        fixture.service.start();
        fixture.service.stop();

        verify(fixture.playerClassService, times(2)).setPlayerListNameUpdatesEnabled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## RPGからロビーへの保存付き転送
     * 検証契約: player-stateのSQL ACKが失敗した場合はProxy接続要求を送らず、現在チャンネルで操作凍結を解除する。
     */
    @Test
    void keepsPlayerOnCurrentChannelWhenBoundarySaveFails() {
        Fixture fixture = new Fixture(true);
        UUID accountId = UUID.randomUUID();
        Player bukkit = mock(Player.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(bukkit.getUniqueId()).thenReturn(UUID.randomUUID());
        when(bukkit.isOnline()).thenReturn(true);
        when(player.getBukkit()).thenReturn(bukkit);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(fixture.playerService.saveForChannelTransfer(player))
            .thenReturn(CompletableFuture.completedFuture(false));
        when(fixture.questService.flushState(accountId))
            .thenReturn(CompletableFuture.completedFuture(null));

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(mock(PlayerMessageService.class));
            fixture.service.transferToLobby(player);
        }

        verify(bukkit).closeInventory();
        verify(bukkit).setInvulnerable(true);
        verify(bukkit).setInvulnerable(false);
        verify(bukkit, never()).sendPluginMessage(any(), any(), any());

        // 失敗時に transfer guard が解除され、同じチャンネルから再試行できる。
        fixture.service.transferToLobby(player);
        verify(fixture.playerService, times(2)).saveForChannelTransfer(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## player-state snapshot
     * 検証契約: account切替中はchannel transferを開始せず、同じ旧セッションを二重に解放しない。
     */
    @Test
    void rejectsChannelTransferWhileAccountSwitchOwnsTransition() {
        Fixture fixture = new Fixture(true);
        UUID playerId = UUID.randomUUID();
        Player bukkit = mock(Player.class);
        AstPlayer player = mock(AstPlayer.class);
        when(bukkit.getUniqueId()).thenReturn(playerId);
        when(player.getBukkit()).thenReturn(bukkit);
        fixture.transitionGuard.tryBegin(
            playerId,
            PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH
        );

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(mock(PlayerMessageService.class));
            fixture.service.transferToLobby(player);
        }

        verify(fixture.playerService, never()).saveForChannelTransfer(any());
        assertEquals(
            PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH,
            fixture.transitionGuard.current(playerId)
        );
    }

    private static final class Fixture {
        private final PlayerClassService playerClassService = mock(PlayerClassService.class);
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final FileConfiguration config = mock(FileConfiguration.class);
        private final Server server = mock(Server.class);
        private final Messenger messenger = mock(Messenger.class);
        private final PluginManager pluginManager = mock(PluginManager.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final BukkitTask metadataTask = mock(BukkitTask.class);
        private final PlayerService playerService = mock(PlayerService.class);
        private final QuestService questService = mock(QuestService.class);
        private final PlayerSessionTransitionGuard transitionGuard = new PlayerSessionTransitionGuard();
        private final NetworkBridgeService service;

        private Fixture(boolean networkEnabled) {
            when(plugin.getConfig()).thenReturn(config);
            when(config.getBoolean("network.enabled", true)).thenReturn(networkEnabled);
            when(config.getString("network.channelName", "dev")).thenReturn("dev");
            when(config.getString("network.lobbyServer", "lobby")).thenReturn("lobby");
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getPlayerService()).thenReturn(playerService);
            when(server.getMessenger()).thenReturn(messenger);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(100L)))
                .thenReturn(metadataTask);
            doAnswer(invocation -> {
                invocation.getArgument(1, Runnable.class).run();
                return mock(BukkitTask.class);
            }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            service = new NetworkBridgeService(
                plugin,
                playerClassService,
                mock(AfkService.class),
                questService,
                transitionGuard
            );
        }
    }
}
