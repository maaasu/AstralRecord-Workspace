package io.github.maaasu.astralRecord.feature.discord.service;

import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DiscordSrvChatBridgeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_5-例外・ログ・運用.md
     * 章・見出し: # 03_5-例外・ログ・運用 > ## 4. chat・ダイレクトメッセージ
     * 検証契約: plugin.whitelistEnabled が有効な間は、DiscordSRV標準のMinecraft→Discord経路も中継しない。
     */
    @Test
    void maintenanceModeCancelsDiscordSrvAutomaticGameChat() throws Exception {
        DiscordSrvChatBridge bridge = newBridge();
        PluginManager pluginManager = mock(PluginManager.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            when(pluginManager.isPluginEnabled("DiscordSRV")).thenReturn(false);

            bridge.setMaintenanceMode(true);

            GameChatMessagePreProcessEvent event = new GameChatMessagePreProcessEvent(
                "global",
                Component.text("hello"),
                mock(Player.class),
                null
            );

            bridge.onGameChatMessagePreProcess(event);

            assertTrue(event.isCancelled());
        }
    }

    private DiscordSrvChatBridge newBridge() throws Exception {
        Constructor<DiscordSrvChatBridge> constructor = DiscordSrvChatBridge.class.getDeclaredConstructor(
            io.github.maaasu.astralRecord.AstralRecord.class,
            PlayerMessageService.class,
            String.class,
            int.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(null, new PlayerMessageService(), "global-channel", 256);
    }
}
