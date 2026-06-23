package io.github.maaasu.astralRecord.feature.player.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstPlayerAccountModeTest extends MockBukkitTestBase {

    @Test
    void adminModeRestoresSurvivalFromAdventureMode() {
        PlayerMock player = server().addPlayer();
        player.setGameMode(GameMode.ADVENTURE);
        AstPlayer astPlayer = new AstPlayer(player, userModel(), accountModel(AccountMode.PLAYER));

        astPlayer.applyAccountMode(accountModel(AccountMode.ADMIN));

        assertEquals(GameMode.SURVIVAL, player.getGameMode());
    }

    @Test
    void nonPlayerModeKeepsCreativeModeWhenAlreadyCreative() {
        PlayerMock player = server().addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        AstPlayer astPlayer = new AstPlayer(player, userModel(), accountModel(AccountMode.PLAYER));
        player.setGameMode(GameMode.CREATIVE);

        astPlayer.applyAccountMode(accountModel(AccountMode.ADMIN));

        assertEquals(GameMode.CREATIVE, player.getGameMode());
    }

    private UserModel userModel() {
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
            0,
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
