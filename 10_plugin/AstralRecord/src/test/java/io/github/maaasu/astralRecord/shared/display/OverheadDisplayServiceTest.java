package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverheadDisplayServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 7. 頭上表示
     * 検証契約: スキルツリーワールド内のプレイヤー用 TextDisplay は生成せず、退出後は通常どおり再生成する。
     */
    @Test
    void suppressesPlayerDisplayInSkillTreeWorldAndRestoresItAfterLeaving() {
        PluginMock plugin = MockBukkit.createMockPlugin("OverheadDisplayServiceTest");
        World baseWorld = server().addSimpleWorld("base");
        World skillTreeWorld = server().addSimpleWorld("skill-tree");
        Player first = server().addPlayer("first");
        Player second = server().addPlayer("second");
        first.teleport(new Location(baseWorld, 0.0D, 64.0D, 0.0D));
        second.teleport(new Location(skillTreeWorld, 0.0D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        DisplayTextService.ManagedTextDisplay firstDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        DisplayTextService.ManagedTextDisplay secondDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        when(displayTextService.create(any(DisplayAnchor.class), any(DisplayTextOptions.class)))
                .thenReturn(firstDisplay, secondDisplay);
        OverheadDisplayService service = new OverheadDisplayService(
                displayTextService,
                mock(StatusService.class),
                mobService,
                mock(PlayerClassService.class),
                world -> world.equals(skillTreeWorld)
        );

        service.start(plugin);
        server().getScheduler().performOneTick();
        verify(displayTextService).create(any(DisplayAnchor.class), any(DisplayTextOptions.class));

        second.teleport(new Location(baseWorld, 0.0D, 64.0D, 0.0D));
        server().getScheduler().performTicks(5);
        verify(displayTextService, times(2)).create(any(DisplayAnchor.class), any(DisplayTextOptions.class));

        second.teleport(new Location(skillTreeWorld, 0.0D, 64.0D, 0.0D));
        server().getScheduler().performTicks(5);
        verify(secondDisplay).destroy();

        service.stop();
    }
}
