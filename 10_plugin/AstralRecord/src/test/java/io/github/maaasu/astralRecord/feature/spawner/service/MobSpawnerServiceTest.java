package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSpawnerServiceTest extends MockBukkitTestBase {

    @Test
    void spawnerVisualIsVisibleOnlyInAdminAccountMode() {
        MobSpawnerService service = service();

        AstPlayer playerModeWithPermission = astPlayer(AccountMode.PLAYER, true);
        AstPlayer builderMode = astPlayer(AccountMode.BUILDER, true);
        AstPlayer adminMode = astPlayer(AccountMode.ADMIN, false);

        assertFalse(service.canViewSpawnerVisual(playerModeWithPermission));
        assertFalse(service.canViewSpawnerVisual(builderMode));
        assertTrue(service.canViewSpawnerVisual(adminMode));
    }

    private MobSpawnerService service() {
        return new MobSpawnerService(
                PluginMock.builder()
                        .withPluginName("AstralRecordTest")
                        .withPluginVersion("1.0.0")
                        .build(),
                mock(MobService.class),
                mock(MobSpawnerDefinitionRepository.class),
                mock(MobSpawnerLocationRepository.class)
        );
    }

    private AstPlayer astPlayer(AccountMode mode, boolean hasAdminPermission) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(accountModel(mode));
        when(astPlayer.hasAdminPermission()).thenReturn(hasAdminPermission);
        return astPlayer;
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
