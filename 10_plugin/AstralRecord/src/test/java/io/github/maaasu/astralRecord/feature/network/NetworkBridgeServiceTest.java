package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private static final class Fixture {
        private final PlayerClassService playerClassService = mock(PlayerClassService.class);
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final FileConfiguration config = mock(FileConfiguration.class);
        private final Server server = mock(Server.class);
        private final Messenger messenger = mock(Messenger.class);
        private final PluginManager pluginManager = mock(PluginManager.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final BukkitTask metadataTask = mock(BukkitTask.class);
        private final NetworkBridgeService service;

        private Fixture(boolean networkEnabled) {
            when(plugin.getConfig()).thenReturn(config);
            when(config.getBoolean("network.enabled", true)).thenReturn(networkEnabled);
            when(config.getString("network.channelName", "dev")).thenReturn("dev");
            when(config.getString("network.lobbyServer", "lobby")).thenReturn("lobby");
            when(plugin.getServer()).thenReturn(server);
            when(server.getMessenger()).thenReturn(messenger);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(100L)))
                .thenReturn(metadataTask);
            service = new NetworkBridgeService(plugin, playerClassService, mock(AfkService.class));
        }
    }
}
