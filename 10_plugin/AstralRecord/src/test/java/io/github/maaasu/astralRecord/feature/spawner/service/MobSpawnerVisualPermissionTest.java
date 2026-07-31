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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_0-概要.md
     * 章・見出し: # 12_0-概要 > ## 3. 構成要素（実装単位）
     * 検証契約: user.permission=99ならaccount modeに関係なくMob spawner packet visualを見せる。
     */
    @Test
    void adminPermissionCanViewSpawnerVisualRegardlessOfAccountMode() {
        MobSpawnerService service = service();
        AstPlayer administrator = mock(AstPlayer.class);
        when(administrator.hasAdminPermission()).thenReturn(true);

        assertTrue(service.canViewSpawnerVisual(administrator));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_0-概要.md
     * 章・見出し: # 12_0-概要 > ## 3. 構成要素（実装単位）
     * 検証契約: admin permissionなしplayerへMob spawner packet visualを見せない。
     */
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
