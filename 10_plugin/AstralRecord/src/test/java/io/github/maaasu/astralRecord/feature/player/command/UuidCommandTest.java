package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UuidCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 5. UUID 表示
     * 検証契約: 引数を省略したプレイヤー実行では実行者自身の UUID を表示し、UUID 部分だけが COPY_TO_CLIPBOARD で同じ UUID をコピーする。
     */
    @Test
    void noArgumentUsesSenderUuidAndMakesUuidCopyable() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000701");
        Player sender = onlinePlayer("Alice", uuid);

        new UuidCommand().onCommand(sender, null, "uuid", new String[0]);

        Component message = captureMessage(sender);
        assertEquals(
            "[AstralRecord] Alice の UUID: " + uuid + " （クリックでコピー）",
            PlainTextComponentSerializer.plainText().serialize(message)
        );
        assertTrue(hasCopyToClipboard(message, uuid.toString()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 5. UUID 表示
     * 検証契約: プレイヤー名を指定した実行では完全一致したオンライン対象プレイヤーの UUID を表示し、対象 UUID のコピー操作を保持する。
     */
    @Test
    void namedPlayerUsesExactOnlineTarget() {
        Player sender = onlinePlayer("Alice", UUID.fromString("00000000-0000-0000-0000-000000000702"));
        UUID targetUuid = UUID.fromString("00000000-0000-0000-0000-000000000703");
        Player target = onlinePlayer("Bob", targetUuid);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Bob")).thenReturn(target);

            new UuidCommand().onCommand(sender, null, "uuid", new String[] {"Bob"});
        }

        Component message = captureMessage(sender);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(message).contains(targetUuid.toString()));
        assertTrue(hasCopyToClipboard(message, targetUuid.toString()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 5. UUID 表示
     * 検証契約: UUID コマンドは権限レベルを要求せず、プレイヤー専用制限も持たない。
     */
    @Test
    void uuidCommandIsAvailableToAllSenders() {
        UuidCommand command = new UuidCommand();

        assertEquals(AstCommand.PERMISSION_NONE, command.getRequiredPermissionLevel());
        assertFalse(command.isPlayerOnly());
    }

    private Player onlinePlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    private Component captureMessage(Player player) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        return captor.getValue();
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
