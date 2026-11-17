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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * 名前空間の有無にかかわらずバニラのチャットだけを管理対象として認識する。
     */
    @Test
    void recognizesOnlyVanillaChatCommandsForConversion() {
        assertAll(
            () -> assertDirectMessageCommand("/msg player hello", true),
            () -> assertDirectMessageCommand("/minecraft:tell player hello", true),
            () -> assertDirectMessageCommand("/bukkit:w player hello", true),
            () -> assertDirectMessageCommand("/minecraft:whisper player hello", true),
            () -> assertDirectMessageCommand("/message player hello", false),
            () -> assertDirectMessageCommand("/party message hello", false),
            () -> assertTrue(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/say hello")),
            () -> assertTrue(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/minecraft:say hello")),
            () -> assertFalse(ManagedChatEventHandler.isVanillaGlobalMessageCommand("/message player hello"))
        );
    }

    /**
     * 引数なしのhelpだけをguide変換対象とし、helpのサブコマンドは残す。
     */
    @Test
    void recognizesOnlyArgumentlessHelpAsGuideShortcut() {
        assertTrue(ManagedChatEventHandler.isGuideHelpCommand("/help"));
        assertTrue(ManagedChatEventHandler.isGuideHelpCommand("/minecraft:help"));
        assertFalse(ManagedChatEventHandler.isGuideHelpCommand("/help commands"));
        assertFalse(ManagedChatEventHandler.isGuideHelpCommand("/guide"));
    }

    /**
     * パーティーチャット mode 有効中の通常チャットは party 配信へ切り替え、全体配信しない。
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
    private static void assertDirectMessageCommand(String command, boolean expected) {
        assertEquals(expected, ManagedChatEventHandler.isVanillaDirectMessageCommand(command), command);
    }
}
