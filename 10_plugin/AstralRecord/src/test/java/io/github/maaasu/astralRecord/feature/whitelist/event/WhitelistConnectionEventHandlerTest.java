package io.github.maaasu.astralRecord.feature.whitelist.event;

import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhitelistConnectionEventHandlerTest {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### whitelist 接続前拒否
     * 検証契約: whitelist 外の UUID は接続前イベントを KICK_OTHER とメンテナンス文言で拒否する。
     */
    @Test
    void rejectsPlayerOutsideWhitelistBeforeLogin() throws Exception {
        WhitelistService service = mock(WhitelistService.class);
        AsyncPlayerPreLoginEvent event = mockEvent();
        UUID playerUuid = event.getUniqueId();
        when(service.isAllowed(playerUuid)).thenReturn(false);

        new WhitelistConnectionEventHandler(service).onAsyncPreLogin(event);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), messageCaptor.capture());
        assertEquals("現在メンテナンス中のため、サーバーへ参加できません。", PLAIN_TEXT.serialize(messageCaptor.getValue()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### whitelist 接続前拒否
     * 検証契約: whitelist 内の UUID は接続前イベントを変更せず、そのまま接続処理へ進める。
     */
    @Test
    void keepsWhitelistedPlayerConnectionUndisturbed() throws Exception {
        WhitelistService service = mock(WhitelistService.class);
        AsyncPlayerPreLoginEvent event = mockEvent();
        when(service.isAllowed(event.getUniqueId())).thenReturn(true);

        new WhitelistConnectionEventHandler(service).onAsyncPreLogin(event);

        verify(event, never()).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
    }

    private AsyncPlayerPreLoginEvent mockEvent() throws Exception {
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getAddress()).thenReturn(InetAddress.getLoopbackAddress());
        return event;
    }
}
