package io.github.maaasu.astralRecord.feature.whitelist.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WhitelistTabCompleterTest {

    @Test
    void completesCommandKeywordsForEachArgumentPosition() {
        CommandSender sender = mock(CommandSender.class);
        WhitelistTabCompleter completer = new WhitelistTabCompleter();

        assertAll(
            () -> assertEquals(List.of("enable", "user"),
                completer.onTabComplete(sender, null, "whitelist", new String[] {""})),
            () -> assertEquals(List.of("add", "remove"),
                completer.onTabComplete(sender, null, "whitelist", new String[] {"user", ""})),
            () -> assertEquals(List.of("true", "false"),
                completer.onTabComplete(sender, null, "whitelist", new String[] {"enable", ""}))
        );
    }

    /**
     * user add/remove の第3引数ではオンラインプレイヤー名を補完する。
     */
    @Test
    void completesOnlinePlayerNamesForWhitelistUser() {
        CommandSender sender = mock(CommandSender.class);
        Player player = mock(Player.class);
        org.mockito.Mockito.when(player.getName()).thenReturn("Alice");

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            List<String> completions = new WhitelistTabCompleter()
                .onTabComplete(sender, null, "whitelist", new String[] {"user", "add", ""});

            assertEquals(List.of("Alice"), completions);
        }
    }
}
