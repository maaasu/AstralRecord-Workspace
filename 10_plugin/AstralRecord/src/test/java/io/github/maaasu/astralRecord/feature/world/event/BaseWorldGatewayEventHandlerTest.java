package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseWorldGatewayEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: 同じポータル滞留中にポータルイベントが繰り返し発生しても、転送 GUI の起動要求を一度だけ実行する。
     */
    @Test
    void enteringPortalOpensGuiOnlyOnceForRepeatedPortalEvents() {
        GatewayFixture fixture = fixture(false);
        BaseWorldGatewayEventHandler handler = fixture.handler();

        PlayerPortalEvent portalEvent = new PlayerPortalEvent(
                fixture.player(),
                fixture.gateway(),
                fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        handler.onPlayerPortal(portalEvent);
        handler.onPlayerPortal(new PlayerPortalEvent(
                fixture.player(),
                fixture.gatewayInside(),
                fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        ));

        assertTrue(portalEvent.isCancelled());

        verify(fixture.guiEventHandler(), never()).open(fixture.player());
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: ポータル退避に成功した後、同じプレイヤーが再入場すると転送 GUI を再度起動する。
     */
    @Test
    void reenteringGatewayAfterSuccessfulEvacuationCanOpenGuiAgain() {
        GatewayFixture fixture = fixture(true);
        BaseWorldGatewayEventHandler handler = fixture.handler();

        handler.onPlayerPortal(new PlayerPortalEvent(fixture.player(), fixture.gateway(), fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        server().getScheduler().performOneTick();
        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());

        handler.onPlayerPortal(new PlayerPortalEvent(fixture.player(), fixture.gateway(), fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(2)).open(fixture.player());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: BASE のネザーポータルイベントをキャンセルし、移動イベントがなくても同じ転送 GUI 起動要求へ接続する。
     */
    @Test
    void nonNetherPortalDoesNotOpenGui() {
        GatewayFixture fixture = fixture(true);
        BaseWorldGatewayEventHandler handler = fixture.handler();
        PlayerPortalEvent event = new PlayerPortalEvent(
                fixture.player(),
                fixture.gateway(),
                fixture.outside(),
                PlayerTeleportEvent.TeleportCause.END_PORTAL
        );

        handler.onPlayerPortal(event);

        assertFalse(event.isCancelled());
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), never()).open(fixture.player());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 例外・終了条件
     * 検証契約: ポータルイベント後の退避テレポートに失敗しても、プレイヤーがオンラインで BASE にいる場合は転送 GUI の表示を試行する。
     */
    @Test
    void gatewayGuiOpensWhenEvacuationTeleportFails() {
        GatewayFixture fixture = fixture(false);
        BaseWorldGatewayEventHandler handler = fixture.handler();

        handler.onPlayerPortal(new PlayerPortalEvent(fixture.player(), fixture.gateway(), fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());
    }

    private GatewayFixture fixture(boolean evacuationTeleportSucceeds) {
        PluginMock plugin = MockBukkit.createMockPlugin("BaseWorldGatewayEventHandlerTest");
        Player player = mock(Player.class);
        World world = mock(World.class);
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        OverworldTeleportGuiEventHandler guiEventHandler = mock(OverworldTeleportGuiEventHandler.class);
        UUID playerId = UUID.randomUUID();
        Location outside = new Location(world, 0.1D, 64.0D, 0.1D);
        Location gateway = new Location(world, 1.1D, 64.0D, 0.1D);
        Location gatewayInside = new Location(world, 1.9D, 64.0D, 0.9D);
        Location spawn = new Location(world, 5.0D, 64.0D, 5.0D);
        Location[] currentLocation = {gateway};

        when(world.getSpawnLocation()).thenReturn(spawn);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(invocation -> currentLocation[0]);
        when(player.isOnline()).thenReturn(true);
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                .thenAnswer(invocation -> {
                    if (evacuationTeleportSucceeds) {
                        currentLocation[0] = invocation.getArgument(0);
                    }
                    return evacuationTeleportSucceeds;
                });
        when(teleportService.isBaseWorld(world)).thenReturn(true);
        when(guiEventHandler.isOpen(player)).thenReturn(false);
        when(guiEventHandler.open(player)).thenReturn(true);

        BaseWorldGatewayEventHandler handler = new BaseWorldGatewayEventHandler(
                plugin,
                teleportService,
                guiEventHandler
        );
        return new GatewayFixture(
                plugin,
                player,
                outside,
                gateway,
                gatewayInside,
                handler,
                guiEventHandler
        );
    }

    private record GatewayFixture(
            Plugin plugin,
            Player player,
            Location outside,
            Location gateway,
            Location gatewayInside,
            BaseWorldGatewayEventHandler handler,
            OverworldTeleportGuiEventHandler guiEventHandler
    ) {
    }
}
