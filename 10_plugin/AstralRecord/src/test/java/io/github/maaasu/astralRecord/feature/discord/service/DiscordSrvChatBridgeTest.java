package io.github.maaasu.astralRecord.feature.discord.service;

import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_5-例外・ログ・運用.md
     * 章・見出し: # 03_5-例外・ログ・運用 > ## 4. chat・ダイレクトメッセージ
     * 検証契約: whitelist 有効時は DiscordSRV のプレイヤー参加・初回参加・退出通知を抑制し、解除時に元の設定へ戻す。
     */
    @Test
    void maintenanceModeSuppressesAndRestoresPlayerLifecycleMessages() {
        DynamicConfig config = mock(DynamicConfig.class);
        when(config.getString(anyString())).thenReturn("original");
        when(config.getOptionalBoolean(anyString())).thenAnswer(invocation ->
            Optional.of(!"MinecraftPlayerLeaveMessage.Enabled".equals(invocation.getArgument(0)))
        );

        DiscordSrvChatBridge.setServerLifecycleMessagesSuppressed(config, true);
        DiscordSrvChatBridge.setServerLifecycleMessagesSuppressed(config, true);

        verify(config, times(2)).setRuntimeValue("MinecraftPlayerJoinMessage.Enabled", false);
        verify(config, times(2)).setRuntimeValue("MinecraftPlayerFirstJoinMessage.Enabled", false);
        verify(config, times(2)).setRuntimeValue("MinecraftPlayerLeaveMessage.Enabled", false);
        verify(config, times(2)).setRuntimeValue("DiscordChatChannelMinecraftToDiscord", false);
        verify(config, times(1)).getOptionalBoolean("MinecraftPlayerJoinMessage.Enabled");
        verify(config, times(1)).getOptionalBoolean("MinecraftPlayerFirstJoinMessage.Enabled");
        verify(config, times(1)).getOptionalBoolean("MinecraftPlayerLeaveMessage.Enabled");
        verify(config, times(1)).getOptionalBoolean("DiscordChatChannelMinecraftToDiscord");

        DiscordSrvChatBridge.setServerLifecycleMessagesSuppressed(config, false);

        verify(config).setRuntimeValue("MinecraftPlayerJoinMessage.Enabled", true);
        verify(config).setRuntimeValue("MinecraftPlayerFirstJoinMessage.Enabled", true);
        verify(config, times(3)).setRuntimeValue("MinecraftPlayerLeaveMessage.Enabled", false);
        verify(config).setRuntimeValue("DiscordChatChannelMinecraftToDiscord", true);
        verify(config, never())
            .setRuntimeValue("DiscordChatChannelDiscordToMinecraft", false);
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
