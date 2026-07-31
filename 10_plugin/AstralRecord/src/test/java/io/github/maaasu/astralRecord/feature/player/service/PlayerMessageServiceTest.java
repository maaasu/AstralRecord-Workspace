package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerMessageServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### システムメッセージ送信
     * 検証契約: 共通タグ直後に半角空白1文字を置く。
     */
    @Test
    void systemMessagePlacesSpaceAfterCommonTag() {
        Player player = onlinePlayer();
        PlayerMessageService service = new PlayerMessageService();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of());
            service.send(player, PlayerMsgId.P_5280);

            Component sent = captureMessage(player);
            assertTrue(PlainTextComponentSerializer.plainText().serialize(sent)
                .startsWith("[AstralRecord] オートセーブ"));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### クリック可能メッセージ送信
     * 検証契約: GUI導線messageに指定slash commandのrunCommand click eventを保持する。
     */
    @Test
    void clickableMessageKeepsGuiCommand() {
        Player player = onlinePlayer();
        PlayerMessageService service = new PlayerMessageService();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of());
            service.sendClickable(player, PlayerMsgId.P_5600, "/menu guide");

            assertTrue(hasRunCommand(captureMessage(player), "/menu guide"));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### 全体チャット配信
     * 検証契約: 全体chatで3文字短縮class tagをplayer名より前に置く。
     */
    @Test
    void globalChatPlacesShortClassNameBeforePlayerName() {
        Player sender = onlinePlayer();
        when(sender.getName()).thenReturn("Alice");
        PlayerClassService classService = mock(PlayerClassService.class);
        when(classService.getShortDisplayName("mage")).thenReturn("§b魔術師");
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getClassId()).thenReturn("mage");
        PlayerMessageService service = new PlayerMessageService(classService);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of(sender));
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(astPlayer);

            service.broadcastGlobalChat(sender, "hello");

            assertEquals(
                "[全体] [魔術師] Alice: hello",
                PlainTextComponentSerializer.plainText().serialize(captureMessage(sender))
            );
        }
    }

    private Player onlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private Component captureMessage(Player player) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        return captor.getValue();
    }

    private boolean hasRunCommand(Component component, String command) {
        ClickEvent clickEvent = component.clickEvent();
        if (clickEvent != null && clickEvent.action() == ClickEvent.Action.RUN_COMMAND) {
            ClickEvent.Payload.Text payload = assertInstanceOf(
                ClickEvent.Payload.Text.class,
                clickEvent.payload()
            );
            assertEquals(command, payload.value());
            return true;
        }
        return component.children().stream().anyMatch(child -> hasRunCommand(child, command));
    }
}
