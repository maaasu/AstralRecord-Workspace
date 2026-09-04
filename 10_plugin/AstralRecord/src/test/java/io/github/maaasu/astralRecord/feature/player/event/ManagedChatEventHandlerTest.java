package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedChatEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラメッセージコマンド変換
     * 検証契約: msg/tell/w/whisperをnamespace有無にかかわらず管理DMへ変換対象として認識する。
     */
    @Test
    void recognizesVanillaDirectMessageAliasesWithOrWithoutNamespace() {
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/msg player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:tell player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/bukkit:w player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:whisper player hello"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラメッセージコマンド変換
     * 検証契約: sayをnamespace有無にかかわらず管理全体チャットへ変換対象として認識する。
     */
    @Test
    void recognizesVanillaGlobalMessageCommandWithOrWithoutNamespace() {
        assertTrue(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/say hello"));
        assertTrue(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/minecraft:say hello"));
        assertFalse(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/message player hello"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラメッセージコマンド変換
     * 検証契約: 引数なしのhelpだけをguide変換対象とし、helpのサブコマンドは残す。
     */
    @Test
    void recognizesOnlyArgumentlessHelpAsGuideShortcut() {
        assertTrue(ManagedChatEventHandler.isGuideHelpCommand("/help"));
        assertTrue(ManagedChatEventHandler.isGuideHelpCommand("/minecraft:help"));
        assertFalse(ManagedChatEventHandler.isGuideHelpCommand("/help commands"));
        assertFalse(ManagedChatEventHandler.isGuideHelpCommand("/guide"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラメッセージコマンド変換
     * 検証契約: AstralRecord管理のmessageコマンドはバニラ変換対象にしない。
     */
    @Test
    void keepsManagedMessageCommandAvailable() {
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/message player hello"));
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/party message hello"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 全体チャット受付
     * 検証契約: パーティーチャット mode 有効中の通常チャットは party 配信へ切り替え、全体配信しない。
     */
    @Test
    void routesNormalChatToEnabledPartyChatMode() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PartyService partyService = mock(PartyService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AsyncChatEvent event = mock(AsyncChatEvent.class);
        Player sender = mock(Player.class);
        UUID senderId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getPartyService()).thenReturn(partyService);
        when(event.getPlayer()).thenReturn(sender);
        when(event.message()).thenReturn(Component.text("hello"));
        when(sender.getUniqueId()).thenReturn(senderId);
        when(partyService.isPartyChatEnabled(senderId)).thenReturn(true);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        });

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new ManagedChatEventHandler(plugin).onAsyncChat(event);
        }

        verify(event).setCancelled(true);
        verify(partyService).broadcastPartyChat(sender, "hello");
        verify(messageService, never()).broadcastGlobalChat(sender, "hello");
    }
}
