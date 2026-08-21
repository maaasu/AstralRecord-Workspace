package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.discord.service.GlobalChatBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

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
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: クリック可能メッセージの置換引数にプレイヤー名が含まれても、指定コマンド以外のクリック操作を付与しない。
     */
    @Test
    void clickableMessageDoesNotReplaceCommandWithPlayerInfo() {
        Player player = onlinePlayer();
        Player inviter = onlinePlayer();
        when(inviter.getName()).thenReturn("Alice");
        PlayerMessageService service = new PlayerMessageService();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of());
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(inviter);
            service.sendClickable(player, PlayerMsgId.P_5908, "/party accept Alice", "Alice");

            Component sent = captureMessage(player);
            assertTrue(hasRunCommand(sent, "/party accept Alice"));
            assertTrue(hasNoRunCommand(sent, "/player info Alice"));
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### 全体チャット配信
     * 検証契約: 全体チャット本文の {@code &c} をカラーコードとして解釈せず、Discord中継にも同じ本文を渡す。
     */
    @Test
    void globalChatKeepsAmpersandColorCodeLiteralAndPublishesIt() {
        Player sender = onlinePlayer();
        when(sender.getName()).thenReturn("Alice");
        GlobalChatBridge bridge = mock(GlobalChatBridge.class);
        PlayerMessageService service = new PlayerMessageService();
        service.setGlobalChatBridge(bridge);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of(sender));

            service.broadcastGlobalChat(sender, "&chello");

            assertEquals(
                "[全体] [---] Alice: &chello",
                PlainTextComponentSerializer.plainText().serialize(captureMessage(sender))
            );
            verify(bridge).publishMinecraftGlobalChat(sender, "&chello");
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### Discord全体チャット配信
     * 検証契約: Discord本文の {@code &c} もカラーコードとして解釈せず、Discordタグ付きで配信する。
     */
    @Test
    void discordGlobalChatKeepsAmpersandColorCodeLiteral() {
        Player recipient = onlinePlayer();
        PlayerMessageService service = new PlayerMessageService();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of(recipient));

            service.broadcastDiscordGlobalChat("Alice", "&chello");

            assertEquals(
                "[Discord] Alice: &chello",
                PlainTextComponentSerializer.plainText().serialize(captureMessage(recipient))
            );
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 9. showitem コマンド
     * 検証契約: 全体チャットのアイテム名全体に COPY_TO_CLIPBOARD を設定し、表示名をそのままコピーする。
     */
    @Test
    void globalItemChatMakesWholeItemNameCopyable() {
        Player sender = onlinePlayer();
        when(sender.getName()).thenReturn("Alice");
        PlayerMessageService service = new PlayerMessageService();
        ItemStack itemTooltip = mock(ItemStack.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Set.of(sender));
            service.broadcastGlobalItemChat(sender, "星詠みの剣", itemTooltip);

            assertTrue(hasCopyToClipboard(captureMessage(sender), "星詠みの剣"));
        }
    }

    private Player onlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
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

    private boolean hasNoRunCommand(Component component, String command) {
        ClickEvent clickEvent = component.clickEvent();
        if (clickEvent != null && clickEvent.action() == ClickEvent.Action.RUN_COMMAND) {
            ClickEvent.Payload.Text payload = assertInstanceOf(
                ClickEvent.Payload.Text.class,
                clickEvent.payload()
            );
            if (command.equals(payload.value())) {
                return false;
            }
        }
        return component.children().stream().allMatch(child -> hasNoRunCommand(child, command));
    }

    private boolean hasCopyToClipboard(Component component, String text) {
        ClickEvent clickEvent = component.clickEvent();
        if (clickEvent != null && clickEvent.action() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
            ClickEvent.Payload.Text payload = assertInstanceOf(
                ClickEvent.Payload.Text.class,
                clickEvent.payload()
            );
            assertEquals(text, payload.value());
            return true;
        }
        return component.children().stream().anyMatch(child -> hasCopyToClipboard(child, text));
    }
}
