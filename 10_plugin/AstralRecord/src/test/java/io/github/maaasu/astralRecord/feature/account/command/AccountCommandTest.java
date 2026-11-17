package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountCommandTest {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * /account は ADMIN 権限のプレイヤーまたはコンソールだけが利用でき、一般プレイヤーは利用できない。
     */
    @Test
    void requiresAdminPermissionForPlayersAndAllowsConsole() {
        AccountCommand command = new AccountCommand();

        Player regularPlayer = mock(Player.class);
        UUID regularUuid = UUID.randomUUID();
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        when(regularPlayer.getUniqueId()).thenReturn(regularUuid);
        when(regularAstPlayer.getBukkit()).thenReturn(regularPlayer);
        when(regularAstPlayer.hasPermissionLevel(99)).thenReturn(false);
        AstPlayerCache.put(regularAstPlayer);

        assertFalse(command.canUse(regularPlayer));

        Player adminPlayer = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        when(adminPlayer.getUniqueId()).thenReturn(adminUuid);
        when(adminAstPlayer.getBukkit()).thenReturn(adminPlayer);
        when(adminAstPlayer.hasPermissionLevel(99)).thenReturn(true);
        AstPlayerCache.put(adminAstPlayer);

        assertTrue(command.canUse(adminPlayer));

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertTrue(command.canUse(console));
    }

    /**
     * ADMIN 権限を持たないプレイヤーには /account の補完候補を返さない。
     */
    @Test
    void refusesTabCompletionForNonAdminPlayer() {
        Player regularPlayer = mock(Player.class);
        UUID regularUuid = UUID.randomUUID();
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        when(regularPlayer.getUniqueId()).thenReturn(regularUuid);
        when(regularAstPlayer.getBukkit()).thenReturn(regularPlayer);
        when(regularAstPlayer.hasAdminPermission()).thenReturn(false);
        AstPlayerCache.put(regularAstPlayer);

        AccountTabCompleter completer = new AccountTabCompleter();

        assertTrue(completer.onTabComplete(regularPlayer, null, "account", new String[] {""}).isEmpty());

        Player adminPlayer = mock(Player.class);
        UUID adminUuid = UUID.randomUUID();
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        when(adminPlayer.getUniqueId()).thenReturn(adminUuid);
        when(adminAstPlayer.getBukkit()).thenReturn(adminPlayer);
        when(adminAstPlayer.hasAdminPermission()).thenReturn(true);
        AstPlayerCache.put(adminAstPlayer);

        assertEquals(
                List.of("create", "rename", "mode", "delete", "switch", "uuid", "clone", "confirm"),
                completer.onTabComplete(adminPlayer, null, "account", new String[] {""})
        );
    }
}
