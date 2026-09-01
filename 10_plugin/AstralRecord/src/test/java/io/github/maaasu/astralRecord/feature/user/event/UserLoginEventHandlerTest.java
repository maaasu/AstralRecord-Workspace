package io.github.maaasu.astralRecord.feature.user.event;

import io.github.maaasu.astralRecord.feature.user.service.UserService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLoginEventHandlerTest {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-イベント.md
     * 章・見出し: # 01_3-イベント > ## 1. イベント仕様 > ### ログイン前イベント受付
     * 検証契約: UserService が有効BANを示した接続前イベントは KICK_OTHER と規約違反・公式Discord問い合わせ案内のメッセージで拒否する。
     */
    @Test
    void rejectsActiveBanBeforeLoginWithAppealMessage() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        InetAddress address = InetAddress.getLoopbackAddress();
        when(event.getUniqueId()).thenReturn(playerUuid);
        when(event.getName()).thenReturn("Alice");
        when(event.getAddress()).thenReturn(address);
        when(userService.onAsyncPreLogin(playerUuid, "Alice", address.getHostAddress())).thenReturn(false);

        new UserLoginEventHandler(userService).onAsyncPreLogin(event);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(event).disallow(
                org.mockito.ArgumentMatchers.eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER),
                messageCaptor.capture()
        );
        String message = PLAIN_TEXT.serialize(messageCaptor.getValue());
        assertEquals(
                "利用規約の違反が見られるため、あなたはこのサーバーからバンされています。\n"
                        + "身に覚えがない、または異議申し立てがある場合は、公式Discordの問い合わせ窓口からお問い合わせください。",
                message
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-イベント.md
     * 章・見出し: # 01_3-イベント > ## 1. イベント仕様 > ### ログイン前イベント受付
     * 検証契約: UserService が接続許可を示した接続前イベントは BAN拒否を追加せず接続処理を継続する。
     */
    @Test
    void leavesAllowedLoginUndisturbed() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        InetAddress address = InetAddress.getLoopbackAddress();
        when(event.getUniqueId()).thenReturn(playerUuid);
        when(event.getName()).thenReturn("Alice");
        when(event.getAddress()).thenReturn(address);
        when(userService.onAsyncPreLogin(playerUuid, "Alice", address.getHostAddress())).thenReturn(true);

        new UserLoginEventHandler(userService).onAsyncPreLogin(event);

        verify(event, never()).disallow(
                org.mockito.ArgumentMatchers.eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER),
                any(Component.class)
        );
    }
}
