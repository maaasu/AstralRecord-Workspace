package io.github.maaasu.astralrecordlobby;

import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DiscordNetworkBridgeTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体チャット
     * 検証契約: LobbyがキャンセルしたMinecraftチャットはDiscordSRV標準経路へ送らず、独自中継だけを使用する。
     */
    @Test
    void cancelsDiscordSrvRelayRegardlessOfBukkitChatCancellationState() {
        DiscordNetworkBridge bridge = new DiscordNetworkBridge(null, null);
        AsyncChatEvent bukkitEvent = mock(AsyncChatEvent.class);
        when(bukkitEvent.isCancelled()).thenReturn(false);
        GameChatMessagePreProcessEvent event = new GameChatMessagePreProcessEvent(
            "global", Component.text("hello"), mock(Player.class), bukkitEvent);

        bridge.onGameChatMessagePreProcess(event);

        assertTrue(event.isCancelled());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体チャット
     * 検証契約: DiscordSRVイベントから元のBukkitイベントを取得できない場合も、標準Minecraft→Discord経路を抑止する。
     */
    @Test
    void cancelsDiscordSrvRelayWhenTriggeringBukkitEventIsUnavailable() {
        DiscordNetworkBridge bridge = new DiscordNetworkBridge(null, null);
        GameChatMessagePreProcessEvent event = new GameChatMessagePreProcessEvent(
            "global", Component.text("hello"), mock(Player.class));

        bridge.onGameChatMessagePreProcess(event);

        assertTrue(event.isCancelled());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## Discordサーバー接続通知
     * 検証契約: Lobby単位の参加・初回参加・退出通知を停止し、Proxy独自通知との重複を防ぐ。
     */
    @Test
    @SuppressWarnings("deprecation")
    void suppressesDiscordSrvPlayerLifecycleMessages() {
        DynamicConfig config = mock(DynamicConfig.class);

        DiscordNetworkBridge.suppressStandardPlayerLifecycleMessages(config);

        verify(config).setRuntimeValue("MinecraftPlayerJoinMessage.Enabled", false);
        verify(config).setRuntimeValue("MinecraftPlayerFirstJoinMessage.Enabled", false);
        verify(config).setRuntimeValue("MinecraftPlayerLeaveMessage.Enabled", false);
    }
}
