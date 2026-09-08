package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldNaturalSpawnBlockEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## 管理外 Mob 抑止
     * 検証契約: TEMPワールドはロードと転送だけを目的とし、RPG gamerule適用と管理外Mob抑止の対象にしない。
     */
    @Test
    void tempWorldIsNotTreatedAsManagedWorld() {
        World tempWorld = server().addSimpleWorld("temp_preview");
        WorldService worldService = mock(WorldService.class);
        MobService mobService = mock(MobService.class);
        Plugin plugin = mock(Plugin.class);
        WorldMasterData temp = new WorldMasterData(
                1,
                "[temp]temp_preview",
                "[temp]temp_preview",
                WorldType.TEMP,
                "temp_preview",
                "",
                true,
                false,
                0,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "",
                null,
                null,
                null,
                null
        );
        when(worldService.findByBukkitWorld(tempWorld)).thenReturn(temp);

        WorldNaturalSpawnBlockEventHandler handler = new WorldNaturalSpawnBlockEventHandler(
                plugin,
                worldService,
                mobService
        );

        try (MockedStatic<Logger> logger = Mockito.mockStatic(Logger.class)) {
            assertDoesNotThrow(handler::initialize);
        }

        verify(worldService, never()).applyRpgGameRules(any(World.class));
    }
}
