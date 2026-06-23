package io.github.maaasu.astralRecord.feature.waystone.command;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.waystone.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaystoneCommandTest extends MockBukkitTestBase {

    @AfterEach
    void clearCache() {
        AstPlayerCache.clear();
    }

    @Test
    void adminAccountModeCanCreateWaystoneWithoutUserAdminPermission() throws IOException {
        WaystoneService service = mock(WaystoneService.class);
        when(service.create(eq("base"), any(Location.class), eq(false), eq(100L)))
            .thenReturn(new WaystoneDefinition("waystone-1", "base", "world", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F, false, 100L));
        WaystoneCommand command = new WaystoneCommand(service);
        PlayerMock player = server().addPlayer();
        AstPlayerCache.put(astPlayer(player, AccountMode.ADMIN, 0));

        command.onCommand(player, null, "waystone", new String[]{"base"});

        verify(service).create(eq("base"), any(Location.class), anyBoolean(), anyLong());
    }

    @Test
    void playerAccountModeCannotCreateWaystone() throws IOException {
        WaystoneService service = mock(WaystoneService.class);
        WaystoneCommand command = new WaystoneCommand(service);
        PlayerMock player = server().addPlayer();
        AstPlayerCache.put(astPlayer(player, AccountMode.PLAYER, 0));

        command.onCommand(player, null, "waystone", new String[]{"base"});

        verify(service, never()).create(any(), any(), anyBoolean(), anyLong());
    }

    private AstPlayer astPlayer(PlayerMock player, AccountMode mode, int permission) {
        return new AstPlayer(player, userModel(permission), accountModel(mode));
    }

    private UserModel userModel(int permission) {
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        return new UserModel(
            userId,
            "test-user",
            now,
            now,
            "127.0.0.1",
            null,
            false,
            null,
            false,
            permission,
            now,
            now,
            userId,
            userId,
            false
        );
    }

    private AccountModel accountModel(AccountMode mode) {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        return new AccountModel(
            accountId,
            userId,
            "test-account",
            0,
            true,
            mode,
            "{}",
            now,
            now,
            userId,
            userId,
            false,
            1,
            0L
        );
    }
}
