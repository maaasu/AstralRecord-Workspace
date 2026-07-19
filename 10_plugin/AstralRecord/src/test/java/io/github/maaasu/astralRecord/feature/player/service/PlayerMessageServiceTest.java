package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
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
