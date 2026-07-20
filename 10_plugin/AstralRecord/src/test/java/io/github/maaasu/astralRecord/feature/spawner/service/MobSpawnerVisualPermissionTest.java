package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSpawnerVisualPermissionTest extends MockBukkitTestBase {

    @Test
    void adminPermissionCanViewSpawnerVisualRegardlessOfAccountMode() {
        MobSpawnerService service = service();
        AstPlayer administrator = mock(AstPlayer.class);
        when(administrator.hasAdminPermission()).thenReturn(true);

        assertTrue(service.canViewSpawnerVisual(administrator));
    }

    @Test
    void playerWithoutAdminPermissionCannotViewSpawnerVisual() {
        MobSpawnerService service = service();
        AstPlayer player = mock(AstPlayer.class);
        when(player.hasAdminPermission()).thenReturn(false);

        assertFalse(service.canViewSpawnerVisual(player));
        assertFalse(service.canViewSpawnerVisual(null));
    }

    private MobSpawnerService service() {
        return new MobSpawnerService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                mock(MobService.class),
                mock(PlayerRegionService.class),
                mock(MobSpawnerDefinitionRepository.class),
                mock(MobSpawnerLocationRepository.class)
        );
    }
}
