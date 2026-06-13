package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.event.world.WorldLoadEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NpcPlacementWorldEventHandlerTest extends MockBukkitTestBase {

    @Test
    void worldLoadDefersNpcSpawnUntilNextTick() {
        var world = server().addSimpleWorld("npc_world");
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        NpcPlacementService placementService = mock(NpcPlacementService.class);
        NpcPlacementWorldEventHandler handler = new NpcPlacementWorldEventHandler(plugin, placementService);

        handler.onWorldLoad(new WorldLoadEvent(world));

        verify(placementService, never()).spawnForWorld(world);

        server().getScheduler().performTicks(1);

        verify(placementService).spawnForWorld(world);
    }
}
