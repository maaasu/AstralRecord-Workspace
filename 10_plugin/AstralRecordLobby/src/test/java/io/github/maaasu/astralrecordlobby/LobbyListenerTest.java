package io.github.maaasu.astralrecordlobby;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"deprecation", "removal"})
class LobbyListenerTest {
    @Test
    void detectsOnlyCoordinatesBelowWorldMinimum() {
        assertTrue(LobbyListener.isBelowWorld(-64, -64.01));
        assertFalse(LobbyListener.isBelowWorld(-64, -64.0));
        assertFalse(LobbyListener.isBelowWorld(-64, 0.0));
    }

    @Test
    void appliesPlayerRotationWithoutChangingSpawnLocation() {
        Location spawn = new Location(null, 10.5, 72.0, -4.5, 15.0F, 5.0F);

        Location destination = LobbyListener.spawnWithRotation(spawn, 120.0F, -20.0F);

        assertNotSame(spawn, destination);
        assertEquals(10.5, destination.getX());
        assertEquals(72.0, destination.getY());
        assertEquals(-4.5, destination.getZ());
        assertEquals(120.0F, destination.getYaw());
        assertEquals(-20.0F, destination.getPitch());
        assertEquals(15.0F, spawn.getYaw());
        assertEquals(5.0F, spawn.getPitch());
    }

    @Test
    void queuesOneVoidReturnAndAllowsRetryAfterFailedTeleport() {
        AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        ServerSelector selector = mock(ServerSelector.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        UUID playerId = UUID.randomUUID();
        Location spawn = new Location(world, 10.5, 72.0, -4.5);
        when(plugin.isAdmin(player)).thenReturn(false);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getYaw()).thenReturn(120.0F);
        when(player.getPitch()).thenReturn(-20.0F);
        when(player.isOnline()).thenReturn(true);
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(false);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getSpawnLocation()).thenReturn(spawn);
        LobbyListener listener = new LobbyListener(plugin, selector);
        PlayerMoveEvent first = moveEvent(player, world, -65.0);
        PlayerMoveEvent duplicate = moveEvent(player, world, -66.0);

        listener.onMove(first);
        listener.onMove(duplicate);

        assertTrue(first.isCancelled());
        assertTrue(duplicate.isCancelled());
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), tasks.capture());
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));

        tasks.getValue().run();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(player).teleport(destination.capture(), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(spawn.getX(), destination.getValue().getX());
        assertEquals(spawn.getY(), destination.getValue().getY());
        assertEquals(spawn.getZ(), destination.getValue().getZ());
        assertEquals(120.0F, destination.getValue().getYaw());
        assertEquals(-20.0F, destination.getValue().getPitch());

        listener.onMove(moveEvent(player, world, -65.0));
        verify(scheduler, times(2)).runTask(eq(plugin), any(Runnable.class));
    }

    @Test
    void leavesAdministratorVoidMovementUntouched() {
        AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        ServerSelector selector = mock(ServerSelector.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(plugin.isAdmin(player)).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-64);
        LobbyListener listener = new LobbyListener(plugin, selector);
        PlayerMoveEvent event = moveEvent(player, world, -65.0);

        listener.onMove(event);

        assertFalse(event.isCancelled());
        verify(plugin, never()).getServer();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_0-概要.md
     * 章・見出し: # 33_0-概要 > ## 不変条件
     * 検証契約: ロビーの一般プレイヤーと管理者は空腹値の変更を受けず、満腹状態を維持する。
     */
    @Test
    void keepsFoodFullForEveryPlayerInLobby() {
        AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        ServerSelector selector = mock(ServerSelector.class);
        Player regularPlayer = mock(Player.class);
        Player administrator = mock(Player.class);
        when(plugin.isAdmin(administrator)).thenReturn(true);
        LobbyListener listener = new LobbyListener(plugin, selector);
        FoodLevelChangeEvent regularEvent = new FoodLevelChangeEvent(regularPlayer, 14);
        FoodLevelChangeEvent administratorEvent = new FoodLevelChangeEvent(administrator, 12);

        listener.onFood(regularEvent);
        listener.onFood(administratorEvent);

        assertTrue(regularEvent.isCancelled());
        assertTrue(administratorEvent.isCancelled());
        verify(regularPlayer).setFoodLevel(20);
        verify(regularPlayer).setSaturation(20.0F);
        verify(regularPlayer).setExhaustion(0.0F);
        verify(administrator).setFoodLevel(20);
        verify(administrator).setSaturation(20.0F);
        verify(administrator).setExhaustion(0.0F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_0-概要.md
     * 章・見出し: # 33_0-概要 > ## 不変条件
     * 検証契約: ロビーのプレイヤーは消耗度の増加を受けず、満腹度の減少経路を持たない。
     */
    @Test
    void cancelsExhaustionForEveryPlayerInLobby() {
        AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        ServerSelector selector = mock(ServerSelector.class);
        Player player = mock(Player.class);
        EntityExhaustionEvent event = mock(EntityExhaustionEvent.class);
        when(event.getEntity()).thenReturn(player);
        LobbyListener listener = new LobbyListener(plugin, selector);

        listener.onExhaustion(event);

        verify(event).setCancelled(true);
        verify(player).setFoodLevel(20);
        verify(player).setSaturation(20.0F);
        verify(player).setExhaustion(0.0F);
    }

    private static PlayerMoveEvent moveEvent(Player player, World world, double destinationY) {
        return new PlayerMoveEvent(
            player,
            new Location(world, 0.0, destinationY + 1.0, 0.0),
            new Location(world, 0.0, destinationY, 0.0));
    }
}
