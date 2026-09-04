package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.UUID;

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
     * 検証契約: 同じゲート接触中に移動イベントが繰り返し発生しても、転送 GUI の起動要求を一度だけ実行する。
     */
    @Test
    void enteringGatewayOpensGuiOnlyOnceForRepeatedMovement() {
        GatewayFixture fixture = fixture(true);
        BaseWorldGatewayEventHandler handler = fixture.handler();

        handler.onPlayerMove(new PlayerMoveEvent(fixture.player(), fixture.outside(), fixture.gateway()));
        handler.onPlayerMove(new PlayerMoveEvent(fixture.player(), fixture.gateway(), fixture.gatewayInside()));

        PlayerTeleportEvent teleportEvent = new PlayerTeleportEvent(
                fixture.player(),
                fixture.gateway(),
                fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        handler.onPlayerTeleport(teleportEvent);

        assertTrue(teleportEvent.isCancelled());

        verify(fixture.guiEventHandler(), never()).open(fixture.player());
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: BASE のゲートウェイ系テレポートをキャンセルし、移動イベントがなくても同じ転送 GUI 起動要求へ接続する。
     */
    @Test
    void gatewayTeleportIsCancelledAndOpensGuiWhenMoveEventIsAbsent() {
        GatewayFixture fixture = fixture(true);
        BaseWorldGatewayEventHandler handler = fixture.handler();
        PlayerTeleportEvent event = new PlayerTeleportEvent(
                fixture.player(),
                fixture.gateway(),
                fixture.outside(),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );

        handler.onPlayerTeleport(event);

        assertTrue(event.isCancelled());
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 例外・終了条件
     * 検証契約: ゲート接触後の退避テレポートに失敗しても、プレイヤーがオンラインで BASE にいる場合は転送 GUI の表示を試行する。
     */
    @Test
    void gatewayGuiOpensWhenEvacuationTeleportFails() {
        GatewayFixture fixture = fixture(false);
        BaseWorldGatewayEventHandler handler = fixture.handler();

        handler.onPlayerMove(new PlayerMoveEvent(fixture.player(), fixture.outside(), fixture.gateway()));
        server().getScheduler().performOneTick();

        verify(fixture.guiEventHandler(), times(1)).open(fixture.player());
    }

    private GatewayFixture fixture(boolean evacuationTeleportSucceeds) {
        PluginMock plugin = MockBukkit.createMockPlugin("BaseWorldGatewayEventHandlerTest");
        Player player = mock(Player.class);
        World world = mock(World.class);
        Block outsideBlock = mock(Block.class);
        Block gatewayBlock = mock(Block.class);
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        OverworldTeleportGuiEventHandler guiEventHandler = mock(OverworldTeleportGuiEventHandler.class);
        UUID playerId = UUID.randomUUID();
        Location outside = new Location(world, 0.1D, 64.0D, 0.1D);
        Location gateway = new Location(world, 1.1D, 64.0D, 0.1D);
        Location gatewayInside = new Location(world, 1.9D, 64.0D, 0.9D);
        Location spawn = new Location(world, 5.0D, 64.0D, 5.0D);

        when(outsideBlock.getType()).thenReturn(Material.STONE);
        when(gatewayBlock.getType()).thenReturn(Material.NETHER_PORTAL);
        when(world.getBlockAt(0, 64, 0)).thenReturn(outsideBlock);
        when(world.getBlockAt(1, 64, 0)).thenReturn(gatewayBlock);
        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            return location.getBlockX() == 1 ? gatewayBlock : outsideBlock;
        });
        when(world.getSpawnLocation()).thenReturn(spawn);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(gateway);
        when(player.isOnline()).thenReturn(true);
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                .thenReturn(evacuationTeleportSucceeds);
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
