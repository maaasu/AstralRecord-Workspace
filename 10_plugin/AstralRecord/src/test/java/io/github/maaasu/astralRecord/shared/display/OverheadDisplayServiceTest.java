package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverheadDisplayServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウントモード変更・オンライン反映
     * 検証契約: ADMIN モードのプレイヤー用 TextDisplay は生成しない。
     */
    @Test
    void doesNotCreatePlayerDisplayForAdministratorMode() {
        PluginMock plugin = MockBukkit.createMockPlugin("OverheadDisplayAdminTest");
        World world = server().addSimpleWorld("base");
        var admin = server().addPlayer("admin");
        admin.teleport(new Location(world, 0.0D, 64.0D, 0.0D));
        AstPlayerCache.put(DesignTestFixtures.astPlayer(admin, AccountMode.ADMIN));

        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        OverheadDisplayService service = new OverheadDisplayService(
                displayTextService,
                mock(StatusService.class),
                mobService,
                mock(PlayerClassService.class),
                ignored -> false
        );

        try {
            service.start(plugin);
            server().getScheduler().performOneTick();

            verify(displayTextService, never()).create(any(DisplayAnchor.class), any(DisplayTextOptions.class));
        } finally {
            service.stop();
            AstPlayerCache.remove(admin.getUniqueId());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 7. 頭上表示
     * 検証契約: テレポート前にプレイヤーの TextDisplay を破棄し、旧地点に残さない。
     */
    @Test
    void removesPlayerDisplayBeforeTeleport() {
        PluginMock plugin = MockBukkit.createMockPlugin("OverheadDisplayTeleportTest");
        World world = server().addSimpleWorld("base");
        PlayerMock player = server().addPlayer("player");
        player.teleport(new Location(world, 0.0D, 64.0D, 0.0D));
        AstPlayerCache.put(DesignTestFixtures.astPlayer(player, AccountMode.PLAYER));

        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        DisplayTextService.ManagedTextDisplay display = mock(DisplayTextService.ManagedTextDisplay.class);
        when(displayTextService.create(any(DisplayAnchor.class), any(DisplayTextOptions.class))).thenReturn(display);
        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(any(AstPlayer.class))).thenReturn(StatusSnapshot.empty());
        when(statusService.getShieldRechargeState(any(AstPlayer.class))).thenReturn(null);
        when(statusService.getShieldDisplayCapacity(any(AstPlayer.class))).thenReturn(0.0D);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getDisplayName(any())).thenReturn("test");
        OverheadDisplayService service = new OverheadDisplayService(
                displayTextService,
                statusService,
                mobService,
                playerClassService,
                ignored -> false
        );

        try {
            service.start(plugin);
            server().getScheduler().performOneTick();
            new PlayerTeleportDisplayEventHandler(service).onPlayerTeleport(
                    new PlayerTeleportEvent(player, player.getLocation(), new Location(world, 10.0D, 64.0D, 0.0D))
            );

            verify(display).destroy();
        } finally {
            service.stop();
            AstPlayerCache.remove(player.getUniqueId());
        }
    }

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
        PlayerMock first = server().addPlayer("first");
        PlayerMock second = server().addPlayer("second");
        first.teleport(new Location(baseWorld, 0.0D, 64.0D, 0.0D));
        second.teleport(new Location(skillTreeWorld, 0.0D, 64.0D, 0.0D));
        AstPlayerCache.put(DesignTestFixtures.astPlayer(first, AccountMode.PLAYER));
        AstPlayerCache.put(DesignTestFixtures.astPlayer(second, AccountMode.PLAYER));

        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        DisplayTextService.ManagedTextDisplay firstDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        DisplayTextService.ManagedTextDisplay secondDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        when(displayTextService.create(any(DisplayAnchor.class), any(DisplayTextOptions.class)))
                .thenReturn(firstDisplay, secondDisplay);
        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(any(AstPlayer.class))).thenReturn(StatusSnapshot.empty());
        when(statusService.getShieldRechargeState(any(AstPlayer.class))).thenReturn(null);
        when(statusService.getShieldDisplayCapacity(any(AstPlayer.class))).thenReturn(0.0D);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getDisplayName(any())).thenReturn("test");
        OverheadDisplayService service = new OverheadDisplayService(
                displayTextService,
                statusService,
                mobService,
                playerClassService,
                world -> world.equals(skillTreeWorld)
        );

        try {
            service.start(plugin);
            server().getScheduler().performOneTick();
            verify(displayTextService).create(any(DisplayAnchor.class), any(DisplayTextOptions.class));

            second.teleport(new Location(baseWorld, 0.0D, 64.0D, 0.0D));
            server().getScheduler().performTicks(5);
            verify(displayTextService, times(2)).create(any(DisplayAnchor.class), any(DisplayTextOptions.class));

            second.teleport(new Location(skillTreeWorld, 0.0D, 64.0D, 0.0D));
            server().getScheduler().performTicks(5);
            verify(secondDisplay).destroy();
        } finally {
            service.stop();
            AstPlayerCache.remove(first.getUniqueId());
            AstPlayerCache.remove(second.getUniqueId());
        }
    }
}
